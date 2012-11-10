/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.asterix.metadata;

import java.rmi.RemoteException;
import java.util.List;

import edu.uci.ics.asterix.common.functions.FunctionSignature;
import edu.uci.ics.asterix.metadata.api.IAsterixStateProxy;
import edu.uci.ics.asterix.metadata.api.IMetadataManager;
import edu.uci.ics.asterix.metadata.api.IMetadataNode;
import edu.uci.ics.asterix.metadata.bootstrap.MetadataConstants;
import edu.uci.ics.asterix.metadata.entities.DatasourceAdapter;
import edu.uci.ics.asterix.metadata.entities.Dataset;
import edu.uci.ics.asterix.metadata.entities.Datatype;
import edu.uci.ics.asterix.metadata.entities.Dataverse;
import edu.uci.ics.asterix.metadata.entities.Function;
import edu.uci.ics.asterix.metadata.entities.Index;
import edu.uci.ics.asterix.metadata.entities.Node;
import edu.uci.ics.asterix.metadata.entities.NodeGroup;
import edu.uci.ics.asterix.transaction.management.exception.ACIDException;
import edu.uci.ics.asterix.transaction.management.service.transaction.TransactionIDFactory;

/**
 * Provides access to Asterix metadata via remote methods to the metadata node.
 * This metadata manager maintains a local cache of metadata Java objects
 * received from the metadata node, to avoid contacting the metadata node
 * repeatedly. We assume that this metadata manager is the only metadata manager
 * in an Asterix cluster. Therefore, no separate cache-invalidation mechanism is
 * needed at this point.
 * Assumptions/Limitations:
 * The metadata subsystem is started during NC Bootstrap start, i.e., when
 * Asterix is deployed.
 * The metadata subsystem is destroyed in NC Bootstrap end, i.e., when Asterix
 * is undeployed.
 * The metadata subsystem consists of the MetadataManager and the MatadataNode.
 * The MetadataManager provides users access to the metadata.
 * The MetadataNode implements direct access to the storage layer on behalf of
 * the MetadataManager, and translates the binary representation of ADM into
 * Java objects for consumption by the MetadataManager's users.
 * There is exactly one instance of the MetadataManager and of the MetadataNode
 * in the cluster, which may or may not be co-located on the same machine (or in
 * the same JVM).
 * The MetadataManager exists in the same JVM as its user's (e.g., the query
 * compiler).
 * The MetadataNode exists in the same JVM as it's transactional components
 * (LockManager, LogManager, etc.)
 * Users shall access the metadata only through the MetadataManager, and never
 * via the MetadataNode directly.
 * Multiple threads may issue requests to the MetadataManager concurrently. For
 * the sake of accessing metadata, we assume a transaction consists of one
 * thread.
 * Users are responsible for locking the metadata (using the MetadataManager
 * API) before issuing requests.
 * The MetadataNode is responsible for acquiring finer-grained locks on behalf
 * of requests from the MetadataManager. Currently, locks are acquired per
 * BTree, since the BTree does not acquire even finer-grained locks yet
 * internally.
 * The metadata can be queried with AQL DML like any other dataset, but can only
 * be changed with AQL DDL.
 * The transaction ids for metadata transactions must be unique across the
 * cluster, i.e., metadata transaction ids shall never "accidentally" overlap
 * with transaction ids of regular jobs or other metadata transactions.
 */
public class MetadataManager implements IMetadataManager {
    // Set in init().
    public static MetadataManager INSTANCE;

    private final MetadataCache cache = new MetadataCache();
    private IAsterixStateProxy proxy;
    private IMetadataNode metadataNode;

    public MetadataManager(IAsterixStateProxy proxy) {
        if (proxy == null) {
            throw new Error("Null proxy given to MetadataManager.");
        }
        this.proxy = proxy;
        this.metadataNode = null;
    }

    @Override
    public void init() throws RemoteException {
        // Could be synchronized on any object. Arbitrarily chose proxy.
        synchronized (proxy) {
            if (metadataNode != null) {
                return;
            }
            metadataNode = proxy.getMetadataNode();
            if (metadataNode == null) {
                throw new Error("Failed to get the MetadataNode.\n" + "The MetadataNode was configured to run on NC: "
                        + proxy.getAsterixProperties().getMetadataNodeName());
            }
        }
    }

    @Override
    public MetadataTransactionContext beginTransaction() throws RemoteException, ACIDException {
        long txnId = TransactionIDFactory.generateTransactionId();
        metadataNode.beginTransaction(txnId);
        return new MetadataTransactionContext(txnId);
    }

    @Override
    public void commitTransaction(MetadataTransactionContext ctx) throws RemoteException, ACIDException {
        metadataNode.commitTransaction(ctx.getTxnId());
        cache.commit(ctx);
    }

    @Override
    public void abortTransaction(MetadataTransactionContext ctx) throws RemoteException, ACIDException {
        metadataNode.abortTransaction(ctx.getTxnId());
    }

    @Override
    public void lock(MetadataTransactionContext ctx, int lockMode) throws RemoteException, ACIDException {
        metadataNode.lock(ctx.getTxnId(), lockMode);
    }

    @Override
    public void unlock(MetadataTransactionContext ctx) throws RemoteException, ACIDException {
        metadataNode.unlock(ctx.getTxnId());
    }

    @Override
    public void addDataverse(MetadataTransactionContext ctx, Dataverse dataverse) throws MetadataException {
        try {
            metadataNode.addDataverse(ctx.getTxnId(), dataverse);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.addDataverse(dataverse);
    }

    @Override
    public void dropDataverse(MetadataTransactionContext ctx, String dataverseName) throws MetadataException {
        try {
            metadataNode.dropDataverse(ctx.getTxnId(), dataverseName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.dropDataverse(dataverseName);
    }

    @Override
    public Dataverse getDataverse(MetadataTransactionContext ctx, String dataverseName) throws MetadataException {
        // First look in the context to see if this transaction created the
        // requested dataverse itself (but the dataverse is still uncommitted).
        Dataverse dataverse = ctx.getDataverse(dataverseName);
        if (dataverse != null) {
            // Don't add this dataverse to the cache, since it is still
            // uncommitted.
            return dataverse;
        }
        if (ctx.dataverseIsDropped(dataverseName)) {
            // Dataverse has been dropped by this transaction but could still be
            // in the cache.
            return null;
        }
        dataverse = cache.getDataverse(dataverseName);
        if (dataverse != null) {
            // Dataverse is already in the cache, don't add it again.
            return dataverse;
        }
        try {
            dataverse = metadataNode.getDataverse(ctx.getTxnId(), dataverseName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        // We fetched the dataverse from the MetadataNode. Add it to the cache
        // when this transaction commits.
        if (dataverse != null) {
            ctx.addDataverse(dataverse);
        }
        return dataverse;
    }

    @Override
    public List<Dataset> getDataverseDatasets(MetadataTransactionContext ctx, String dataverseName)
            throws MetadataException {
        List<Dataset> dataverseDatasets;
        try {
            // Assuming that the transaction can read its own writes on the
            // metadata node.
            dataverseDatasets = metadataNode.getDataverseDatasets(ctx.getTxnId(), dataverseName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        // Don't update the cache to avoid checking against the transaction's
        // uncommitted datasets.
        return dataverseDatasets;
    }

    @Override
    public void addDataset(MetadataTransactionContext ctx, Dataset dataset) throws MetadataException {
        try {
            metadataNode.addDataset(ctx.getTxnId(), dataset);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.addDataset(dataset);
    }

    @Override
    public void dropDataset(MetadataTransactionContext ctx, String dataverseName, String datasetName)
            throws MetadataException {
        try {
            metadataNode.dropDataset(ctx.getTxnId(), dataverseName, datasetName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.dropDataset(dataverseName, datasetName);
    }

    @Override
    public Dataset getDataset(MetadataTransactionContext ctx, String dataverseName, String datasetName)
            throws MetadataException {
        // First look in the context to see if this transaction created the
        // requested dataset itself (but the dataset is still uncommitted).
        Dataset dataset = ctx.getDataset(dataverseName, datasetName);
        if (dataset != null) {
            // Don't add this dataverse to the cache, since it is still
            // uncommitted.
            return dataset;
        }
        if (ctx.datasetIsDropped(dataverseName, datasetName)) {
            // Dataset has been dropped by this transaction but could still be
            // in the cache.
            return null;
        }

        if (!MetadataConstants.METADATA_DATAVERSE_NAME.equals(dataverseName) && ctx.getDataverse(dataverseName) != null) {
            // This transaction has dropped and subsequently created the same
            // dataverse.
            return null;
        }

        dataset = cache.getDataset(dataverseName, datasetName);
        if (dataset != null) {
            // Dataset is already in the cache, don't add it again.
            return dataset;
        }
        try {
            dataset = metadataNode.getDataset(ctx.getTxnId(), dataverseName, datasetName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        // We fetched the dataset from the MetadataNode. Add it to the cache
        // when this transaction commits.
        if (dataset != null) {
            ctx.addDataset(dataset);
        }
        return dataset;
    }

    @Override
    public List<Index> getDatasetIndexes(MetadataTransactionContext ctx, String dataverseName, String datasetName)
            throws MetadataException {
        List<Index> datsetIndexes;
        try {
            datsetIndexes = metadataNode.getDatasetIndexes(ctx.getTxnId(), dataverseName, datasetName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        return datsetIndexes;
    }

    @Override
    public void addDatatype(MetadataTransactionContext ctx, Datatype datatype) throws MetadataException {
        try {
            metadataNode.addDatatype(ctx.getTxnId(), datatype);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.addDatatype(datatype);
    }

    @Override
    public void dropDatatype(MetadataTransactionContext ctx, String dataverseName, String datatypeName)
            throws MetadataException {
        try {
            metadataNode.dropDatatype(ctx.getTxnId(), dataverseName, datatypeName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.dropDataDatatype(dataverseName, datatypeName);
    }

    @Override
    public Datatype getDatatype(MetadataTransactionContext ctx, String dataverseName, String datatypeName)
            throws MetadataException {
        // First look in the context to see if this transaction created the
        // requested datatype itself (but the datatype is still uncommitted).
        Datatype datatype = ctx.getDatatype(dataverseName, datatypeName);
        if (datatype != null) {
            // Don't add this dataverse to the cache, since it is still
            // uncommitted.
            return datatype;
        }
        if (ctx.datatypeIsDropped(dataverseName, datatypeName)) {
            // Datatype has been dropped by this transaction but could still be
            // in the cache.
            return null;
        }

        if (!MetadataConstants.METADATA_DATAVERSE_NAME.equals(dataverseName) && ctx.getDataverse(dataverseName) != null) {
            // This transaction has dropped and subsequently created the same
            // dataverse.
            return null;
        }

        datatype = cache.getDatatype(dataverseName, datatypeName);
        if (datatype != null) {
            // Datatype is already in the cache, don't add it again.
            return datatype;
        }
        try {
            datatype = metadataNode.getDatatype(ctx.getTxnId(), dataverseName, datatypeName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        // We fetched the datatype from the MetadataNode. Add it to the cache
        // when this transaction commits.
        if (datatype != null) {
            ctx.addDatatype(datatype);
        }
        return datatype;
    }

    @Override
    public void addIndex(MetadataTransactionContext ctx, Index index) throws MetadataException {
        try {
            metadataNode.addIndex(ctx.getTxnId(), index);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
    }

    @Override
    public void addAdapter(MetadataTransactionContext mdTxnCtx, DatasourceAdapter adapter) throws MetadataException {
        try {
            metadataNode.addAdapter(mdTxnCtx.getTxnId(), adapter);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        mdTxnCtx.addAdapter(adapter);

    }

    @Override
    public void dropIndex(MetadataTransactionContext ctx, String dataverseName, String datasetName, String indexName)
            throws MetadataException {
        try {
            metadataNode.dropIndex(ctx.getTxnId(), dataverseName, datasetName, indexName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
    }

    @Override
    public Index getIndex(MetadataTransactionContext ctx, String dataverseName, String datasetName, String indexName)
            throws MetadataException {
        try {
            return metadataNode.getIndex(ctx.getTxnId(), dataverseName, datasetName, indexName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
    }

    @Override
    public void addNode(MetadataTransactionContext ctx, Node node) throws MetadataException {
        try {
            metadataNode.addNode(ctx.getTxnId(), node);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
    }

    @Override
    public void addNodegroup(MetadataTransactionContext ctx, NodeGroup nodeGroup) throws MetadataException {
        try {
            metadataNode.addNodeGroup(ctx.getTxnId(), nodeGroup);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.addNogeGroup(nodeGroup);
    }

    @Override
    public void dropNodegroup(MetadataTransactionContext ctx, String nodeGroupName) throws MetadataException {
        try {
            metadataNode.dropNodegroup(ctx.getTxnId(), nodeGroupName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.dropNodeGroup(nodeGroupName);
    }

    @Override
    public NodeGroup getNodegroup(MetadataTransactionContext ctx, String nodeGroupName) throws MetadataException {
        // First look in the context to see if this transaction created the
        // requested dataverse itself (but the dataverse is still uncommitted).
        NodeGroup nodeGroup = ctx.getNodeGroup(nodeGroupName);
        if (nodeGroup != null) {
            // Don't add this dataverse to the cache, since it is still
            // uncommitted.
            return nodeGroup;
        }
        if (ctx.nodeGroupIsDropped(nodeGroupName)) {
            // NodeGroup has been dropped by this transaction but could still be
            // in the cache.
            return null;
        }
        nodeGroup = cache.getNodeGroup(nodeGroupName);
        if (nodeGroup != null) {
            // NodeGroup is already in the cache, don't add it again.
            return nodeGroup;
        }
        try {
            nodeGroup = metadataNode.getNodeGroup(ctx.getTxnId(), nodeGroupName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        // We fetched the nodeGroup from the MetadataNode. Add it to the cache
        // when this transaction commits.
        if (nodeGroup != null) {
            ctx.addNogeGroup(nodeGroup);
        }
        return nodeGroup;
    }

    @Override
    public void addFunction(MetadataTransactionContext mdTxnCtx, Function function) throws MetadataException {
        try {
            metadataNode.addFunction(mdTxnCtx.getTxnId(), function);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        mdTxnCtx.addFunction(function);
    }

    @Override
    public void dropFunction(MetadataTransactionContext ctx, FunctionSignature functionSignature)
            throws MetadataException {
        try {
            metadataNode.dropFunction(ctx.getTxnId(), functionSignature);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        ctx.dropFunction(functionSignature);
    }

    @Override
    public Function getFunction(MetadataTransactionContext ctx, FunctionSignature functionSignature)
            throws MetadataException {
        // First look in the context to see if this transaction created the
        // requested dataset itself (but the dataset is still uncommitted).
        Function function = ctx.getFunction(functionSignature);
        if (function != null) {
            // Don't add this dataverse to the cache, since it is still
            // uncommitted.
            return function;
        }
        if (ctx.functionIsDropped(functionSignature)) {
            // Function has been dropped by this transaction but could still be
            // in the cache.
            return null;
        }
        if (ctx.getDataverse(functionSignature.getNamespace()) != null) {
            // This transaction has dropped and subsequently created the same
            // dataverse.
            return null;
        }
        function = cache.getFunction(functionSignature);
        if (function != null) {
            // Function is already in the cache, don't add it again.
            return function;
        }
        try {
            function = metadataNode.getFunction(ctx.getTxnId(), functionSignature);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        // We fetched the function from the MetadataNode. Add it to the cache
        // when this transaction commits.
        if (function != null) {
            ctx.addFunction(function);
        }
        return function;

    }

    @Override
    public List<Function> getDataverseFunctions(MetadataTransactionContext ctx, String dataverseName)
            throws MetadataException {
        List<Function> dataverseFunctions;
        try {
            // Assuming that the transaction can read its own writes on the
            // metadata node.
            dataverseFunctions = metadataNode.getDataverseFunctions(ctx.getTxnId(), dataverseName);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        // Don't update the cache to avoid checking against the transaction's
        // uncommitted functions.
        return dataverseFunctions;
    }

    @Override
    public void dropAdapter(MetadataTransactionContext ctx, String dataverseName, String name) throws MetadataException {
        try {
            metadataNode.dropAdapter(ctx.getTxnId(), dataverseName, name);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
    }

    @Override
    public DatasourceAdapter getAdapter(MetadataTransactionContext ctx, String dataverseName, String name)
            throws MetadataException {
        DatasourceAdapter adapter = null;
        try {
            adapter = metadataNode.getAdapter(ctx.getTxnId(), dataverseName, name);
        } catch (RemoteException e) {
            throw new MetadataException(e);
        }
        return adapter;
    }

}