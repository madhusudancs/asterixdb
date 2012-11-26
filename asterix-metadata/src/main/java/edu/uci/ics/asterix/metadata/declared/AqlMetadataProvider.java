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

package edu.uci.ics.asterix.metadata.declared;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.common.config.GlobalConfig;
import edu.uci.ics.asterix.common.dataflow.IAsterixApplicationContextInfo;
import edu.uci.ics.asterix.common.parse.IParseFileSplitsDecl;
import edu.uci.ics.asterix.dataflow.data.nontagged.valueproviders.AqlPrimitiveValueProviderFactory;
import edu.uci.ics.asterix.external.adapter.factory.IExternalDatasetAdapterFactory;
import edu.uci.ics.asterix.external.adapter.factory.IFeedDatasetAdapterFactory;
import edu.uci.ics.asterix.external.adapter.factory.IFeedDatasetAdapterFactory.FeedAdapterType;
import edu.uci.ics.asterix.external.adapter.factory.IGenericFeedDatasetAdapterFactory;
import edu.uci.ics.asterix.external.adapter.factory.ITypedFeedDatasetAdapterFactory;
import edu.uci.ics.asterix.external.data.operator.ExternalDataScanOperatorDescriptor;
import edu.uci.ics.asterix.external.dataset.adapter.IDatasourceAdapter;
import edu.uci.ics.asterix.external.dataset.adapter.IFeedDatasourceAdapter;
import edu.uci.ics.asterix.feed.comm.IFeedMessage;
import edu.uci.ics.asterix.feed.mgmt.FeedId;
import edu.uci.ics.asterix.feed.operator.FeedIntakeOperatorDescriptor;
import edu.uci.ics.asterix.feed.operator.FeedMessageOperatorDescriptor;
import edu.uci.ics.asterix.formats.base.IDataFormat;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryComparatorFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlTypeTraitProvider;
import edu.uci.ics.asterix.metadata.MetadataException;
import edu.uci.ics.asterix.metadata.MetadataManager;
import edu.uci.ics.asterix.metadata.MetadataTransactionContext;
import edu.uci.ics.asterix.metadata.bootstrap.AsterixProperties;
import edu.uci.ics.asterix.metadata.bootstrap.MetadataConstants;
import edu.uci.ics.asterix.metadata.entities.Dataset;
import edu.uci.ics.asterix.metadata.entities.DatasourceAdapter;
import edu.uci.ics.asterix.metadata.entities.Datatype;
import edu.uci.ics.asterix.metadata.entities.Dataverse;
import edu.uci.ics.asterix.metadata.entities.ExternalDatasetDetails;
import edu.uci.ics.asterix.metadata.entities.FeedDatasetDetails;
import edu.uci.ics.asterix.metadata.entities.Index;
import edu.uci.ics.asterix.metadata.entities.InternalDatasetDetails;
import edu.uci.ics.asterix.metadata.utils.DatasetUtils;
import edu.uci.ics.asterix.om.functions.AsterixBuiltinFunctions;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.om.util.NonTaggedFormatUtil;
import edu.uci.ics.asterix.runtime.base.AsterixTupleFilterFactory;
import edu.uci.ics.asterix.runtime.formats.FormatUtils;
import edu.uci.ics.asterix.runtime.formats.NonTaggedDataFormat;
import edu.uci.ics.asterix.runtime.transaction.TreeIndexInsertUpdateDeleteOperatorDescriptor;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksAbsolutePartitionConstraint;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.Pair;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.IExpressionRuntimeProvider;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.IFunctionInfo;
import edu.uci.ics.hyracks.algebricks.core.algebra.metadata.IDataSink;
import edu.uci.ics.hyracks.algebricks.core.algebra.metadata.IDataSource;
import edu.uci.ics.hyracks.algebricks.core.algebra.metadata.IDataSourceIndex;
import edu.uci.ics.hyracks.algebricks.core.algebra.metadata.IMetadataProvider;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import edu.uci.ics.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import edu.uci.ics.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import edu.uci.ics.hyracks.algebricks.data.IAWriterFactory;
import edu.uci.ics.hyracks.algebricks.data.IPrinterFactory;
import edu.uci.ics.hyracks.algebricks.runtime.base.IPushRuntimeFactory;
import edu.uci.ics.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import edu.uci.ics.hyracks.algebricks.runtime.operators.std.SinkWriterRuntimeFactory;
import edu.uci.ics.hyracks.api.dataflow.IOperatorDescriptor;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;
import edu.uci.ics.hyracks.api.io.FileReference;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.dataflow.std.file.ConstantFileSplitProvider;
import edu.uci.ics.hyracks.dataflow.std.file.FileScanOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.file.FileSplit;
import edu.uci.ics.hyracks.dataflow.std.file.IFileSplitProvider;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParserFactory;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeDataflowHelperFactory;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeSearchOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.frames.BTreeNSMInteriorFrameFactory;
import edu.uci.ics.hyracks.storage.am.common.api.IPrimitiveValueProviderFactory;
import edu.uci.ics.hyracks.storage.am.common.api.ITreeIndexFrameFactory;
import edu.uci.ics.hyracks.storage.am.common.dataflow.TreeIndexBulkLoadOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.impls.NoOpOperationCallbackProvider;
import edu.uci.ics.hyracks.storage.am.common.ophelpers.IndexOp;
import edu.uci.ics.hyracks.storage.am.common.tuples.TypeAwareTupleWriterFactory;
import edu.uci.ics.hyracks.storage.am.rtree.dataflow.RTreeDataflowHelperFactory;
import edu.uci.ics.hyracks.storage.am.rtree.dataflow.RTreeSearchOperatorDescriptor;

public class AqlMetadataProvider implements IMetadataProvider<AqlSourceId, String> {

    private static Logger LOGGER = Logger.getLogger(AqlMetadataProvider.class.getName());

    private final MetadataTransactionContext mdTxnCtx;
    private boolean isWriteTransaction;
    private Map<String, String[]> stores;
    private Map<String, String> config;
    private IAWriterFactory writerFactory;
    private FileSplit outputFile;
    private long jobTxnId;

    private final Dataverse defaultDataverse;

    private static final Map<String, String> adapterFactoryMapping = initializeAdapterFactoryMapping();

    public String getPropertyValue(String propertyName) {
        return config.get(propertyName);
    }

    public void setConfig(Map<String, String> config) {
        this.config = config;
    }

    public Map<String, String[]> getAllStores() {
        return stores;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public AqlMetadataProvider(MetadataTransactionContext mdTxnCtx, Dataverse defaultDataverse) {
        this.mdTxnCtx = mdTxnCtx;
        this.defaultDataverse = defaultDataverse;
        this.stores = AsterixProperties.INSTANCE.getStores();
    }
    
    public void setJobTxnId(long txnId){
    	this.jobTxnId = txnId;
    }

    public Dataverse getDefaultDataverse() {
        return defaultDataverse;
    }

    public String getDefaultDataverseName() {
        return defaultDataverse == null ? null : defaultDataverse.getDataverseName();
    }

    public void setWriteTransaction(boolean writeTransaction) {
        this.isWriteTransaction = writeTransaction;
    }

    public void setWriterFactory(IAWriterFactory writerFactory) {
        this.writerFactory = writerFactory;
    }

    public MetadataTransactionContext getMetadataTxnContext() {
        return mdTxnCtx;
    }

    public IAWriterFactory getWriterFactory() {
        return this.writerFactory;
    }

    public FileSplit getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(FileSplit outputFile) {
        this.outputFile = outputFile;
    }

    @Override
    public AqlDataSource findDataSource(AqlSourceId id) throws AlgebricksException {
        AqlSourceId aqlId = (AqlSourceId) id;
        try {
            return lookupSourceInMetadata(aqlId);
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
    }

    public boolean isWriteTransaction() {
        return isWriteTransaction;
    }

    @Override
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getScannerRuntime(
            IDataSource<AqlSourceId> dataSource, List<LogicalVariable> scanVariables,
            List<LogicalVariable> projectVariables, boolean projectPushed, IOperatorSchema opSchema,
            IVariableTypeEnvironment typeEnv, JobGenContext context, JobSpecification jobSpec)
            throws AlgebricksException {
        Dataset dataset;
        try {
            dataset = MetadataManager.INSTANCE.getDataset(mdTxnCtx, dataSource.getId().getDataverseName(), dataSource
                    .getId().getDatasetName());

            if (dataset == null) {
                throw new AlgebricksException("Unknown dataset " + dataSource.getId().getDatasetName()
                        + " in dataverse " + dataSource.getId().getDataverseName());
            }
            switch (dataset.getDatasetType()) {
                case FEED:
                    if (dataSource instanceof ExternalFeedDataSource) {
                        return buildExternalDatasetScan(jobSpec, dataset, dataSource);
                    } else {
                        return buildInternalDatasetScan(jobSpec, scanVariables, opSchema, typeEnv, dataset, dataSource,
                                context);

                    }
                case INTERNAL: {
                    return buildInternalDatasetScan(jobSpec, scanVariables, opSchema, typeEnv, dataset, dataSource,
                            context);
                }
                case EXTERNAL: {
                    return buildExternalDatasetScan(jobSpec, dataset, dataSource);
                }
                default: {
                    throw new IllegalArgumentException();
                }
            }
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
    }

    private Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildInternalDatasetScan(JobSpecification jobSpec,
            List<LogicalVariable> outputVars, IOperatorSchema opSchema, IVariableTypeEnvironment typeEnv,
            Dataset dataset, IDataSource<AqlSourceId> dataSource, JobGenContext context) throws AlgebricksException,
            MetadataException {
        AqlSourceId asid = dataSource.getId();
        String dataverseName = asid.getDataverseName();
        String datasetName = asid.getDatasetName();
        Index primaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataverseName, datasetName, datasetName);
        return buildBtreeRuntime(jobSpec, outputVars, opSchema, typeEnv, context, false, dataset,
                primaryIndex.getIndexName(), null, null, true, true);
    }

    private Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildExternalDatasetScan(JobSpecification jobSpec,
            Dataset dataset, IDataSource<AqlSourceId> dataSource) throws AlgebricksException, MetadataException {
        String itemTypeName = dataset.getItemTypeName();
        IAType itemType = MetadataManager.INSTANCE.getDatatype(mdTxnCtx, dataset.getDataverseName(), itemTypeName)
                .getDatatype();
        if (dataSource instanceof ExternalFeedDataSource) {
            return buildFeedIntakeRuntime(jobSpec, dataset);
        } else {
            return buildExternalDataScannerRuntime(jobSpec, itemType,
                    (ExternalDatasetDetails) dataset.getDatasetDetails(), NonTaggedDataFormat.INSTANCE);
        }
    }

    @SuppressWarnings("rawtypes")
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildExternalDataScannerRuntime(
            JobSpecification jobSpec, IAType itemType, ExternalDatasetDetails datasetDetails, IDataFormat format)
            throws AlgebricksException {
        if (itemType.getTypeTag() != ATypeTag.RECORD) {
            throw new AlgebricksException("Can only scan datasets of records.");
        }

        IExternalDatasetAdapterFactory adapterFactory;
        IDatasourceAdapter adapter;
        String adapterName;
        DatasourceAdapter adapterEntity;
        String adapterFactoryClassname;
        try {
            adapterName = datasetDetails.getAdapter();
            adapterEntity = MetadataManager.INSTANCE.getAdapter(mdTxnCtx, MetadataConstants.METADATA_DATAVERSE_NAME,
                    adapterName);
            if (adapterEntity != null) {
                adapterFactoryClassname = adapterEntity.getClassname();
                adapterFactory = (IExternalDatasetAdapterFactory) Class.forName(adapterFactoryClassname).newInstance();
            } else {
                adapterFactoryClassname = adapterFactoryMapping.get(adapterName);
                if (adapterFactoryClassname == null) {
                    throw new AlgebricksException(" Unknown adapter :" + adapterName);
                }
                adapterFactory = (IExternalDatasetAdapterFactory) Class.forName(adapterFactoryClassname).newInstance();
            }

            adapter = ((IExternalDatasetAdapterFactory) adapterFactory).createAdapter(datasetDetails.getProperties(),
                    itemType);
        } catch (AlgebricksException ae) {
            throw ae;
        } catch (Exception e) {
            e.printStackTrace();
            throw new AlgebricksException("unable to load the adapter factory class " + e);
        }

        if (!(adapter.getAdapterType().equals(IDatasourceAdapter.AdapterType.READ) || adapter.getAdapterType().equals(
                IDatasourceAdapter.AdapterType.READ_WRITE))) {
            throw new AlgebricksException("external dataset adapter does not support read operation");
        }
        ARecordType rt = (ARecordType) itemType;

        ISerializerDeserializer payloadSerde = format.getSerdeProvider().getSerializerDeserializer(itemType);
        RecordDescriptor scannerDesc = new RecordDescriptor(new ISerializerDeserializer[] { payloadSerde });

        ExternalDataScanOperatorDescriptor dataScanner = new ExternalDataScanOperatorDescriptor(jobSpec,
                adapterFactoryClassname, datasetDetails.getProperties(), rt, scannerDesc);

        AlgebricksPartitionConstraint constraint = adapter.getPartitionConstraint();
        return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(dataScanner, constraint);
    }

    @SuppressWarnings("rawtypes")
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildScannerRuntime(JobSpecification jobSpec,
            IAType itemType, IParseFileSplitsDecl decl, IDataFormat format) throws AlgebricksException {
        if (itemType.getTypeTag() != ATypeTag.RECORD) {
            throw new AlgebricksException("Can only scan datasets of records.");
        }
        ARecordType rt = (ARecordType) itemType;
        ITupleParserFactory tupleParser = format.createTupleParser(rt, decl);
        FileSplit[] splits = decl.getSplits();
        IFileSplitProvider scannerSplitProvider = new ConstantFileSplitProvider(splits);
        ISerializerDeserializer payloadSerde = format.getSerdeProvider().getSerializerDeserializer(itemType);
        RecordDescriptor scannerDesc = new RecordDescriptor(new ISerializerDeserializer[] { payloadSerde });
        IOperatorDescriptor scanner = new FileScanOperatorDescriptor(jobSpec, scannerSplitProvider, tupleParser,
                scannerDesc);
        String[] locs = new String[splits.length];
        for (int i = 0; i < splits.length; i++) {
            locs[i] = splits[i].getNodeName();
        }
        AlgebricksPartitionConstraint apc = new AlgebricksAbsolutePartitionConstraint(locs);
        return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(scanner, apc);
    }

    @SuppressWarnings("rawtypes")
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildFeedIntakeRuntime(JobSpecification jobSpec,
            Dataset dataset) throws AlgebricksException {

        FeedDatasetDetails datasetDetails = (FeedDatasetDetails) dataset.getDatasetDetails();
        DatasourceAdapter adapterEntity;
        IDatasourceAdapter adapter;
        IFeedDatasetAdapterFactory adapterFactory;
        IAType adapterOutputType;

        try {
            adapterEntity = MetadataManager.INSTANCE.getAdapter(mdTxnCtx, null, datasetDetails.getAdapterFactory());
            adapterFactory = (IFeedDatasetAdapterFactory) Class.forName(adapterEntity.getClassname()).newInstance();
            if (adapterFactory.getFeedAdapterType().equals(FeedAdapterType.TYPED)) {
                adapter = ((ITypedFeedDatasetAdapterFactory) adapterFactory).createAdapter(datasetDetails
                        .getProperties());
                adapterOutputType = ((IFeedDatasourceAdapter) adapter).getAdapterOutputType();
            } else {
                String outputTypeName = datasetDetails.getProperties().get(
                        IGenericFeedDatasetAdapterFactory.KEY_TYPE_NAME);
                adapterOutputType = MetadataManager.INSTANCE.getDatatype(mdTxnCtx, null, outputTypeName).getDatatype();
                adapter = ((IGenericFeedDatasetAdapterFactory) adapterFactory).createAdapter(
                        datasetDetails.getProperties(), adapterOutputType);
            }
        } catch (Exception e) {
            throw new AlgebricksException(e);
        }

        ISerializerDeserializer payloadSerde = NonTaggedDataFormat.INSTANCE.getSerdeProvider()
                .getSerializerDeserializer(adapterOutputType);
        RecordDescriptor feedDesc = new RecordDescriptor(new ISerializerDeserializer[] { payloadSerde });

        FeedIntakeOperatorDescriptor feedIngestor = new FeedIntakeOperatorDescriptor(jobSpec, new FeedId(
                dataset.getDataverseName(), dataset.getDatasetName()), adapterEntity.getClassname(),
                datasetDetails.getProperties(), (ARecordType) adapterOutputType, feedDesc);

        return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(feedIngestor,
                adapter.getPartitionConstraint());
    }

    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildFeedMessengerRuntime(
            AqlMetadataProvider metadataProvider, JobSpecification jobSpec, FeedDatasetDetails datasetDetails,
            String dataverse, String dataset, List<IFeedMessage> feedMessages) throws AlgebricksException {
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> spPc = metadataProvider
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(dataverse, dataset, dataset);
        FeedMessageOperatorDescriptor feedMessenger = new FeedMessageOperatorDescriptor(jobSpec, dataverse, dataset,
                feedMessages);
        return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(feedMessenger, spPc.second);
    }

    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildBtreeRuntime(JobSpecification jobSpec,
            List<LogicalVariable> outputVars, IOperatorSchema opSchema, IVariableTypeEnvironment typeEnv,
            JobGenContext context, boolean retainInput, Dataset dataset, String indexName, int[] lowKeyFields,
            int[] highKeyFields, boolean lowKeyInclusive, boolean highKeyInclusive) throws AlgebricksException {
        boolean isSecondary = true;
        try {
            Index primaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), dataset.getDatasetName());
            if (primaryIndex != null) {
                isSecondary = !indexName.equals(primaryIndex.getIndexName());
            }
            int numPrimaryKeys = DatasetUtils.getPartitioningKeys(dataset).size();
            RecordDescriptor outputRecDesc = JobGenHelper.mkRecordDescriptor(typeEnv, opSchema, context);
            int numKeys = numPrimaryKeys;
            int keysStartIndex = outputRecDesc.getFieldCount() - numKeys - 1;
            if (isSecondary) {
                Index secondaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                        dataset.getDatasetName(), indexName);
                int numSecondaryKeys = secondaryIndex.getKeyFieldNames().size();
                numKeys += numSecondaryKeys;
                keysStartIndex = outputVars.size() - numKeys;
            }
            IBinaryComparatorFactory[] comparatorFactories = JobGenHelper.variablesToAscBinaryComparatorFactories(
                    outputVars, keysStartIndex, numKeys, typeEnv, context);
            ITypeTraits[] typeTraits = null;

            if (isSecondary) {
                typeTraits = JobGenHelper.variablesToTypeTraits(outputVars, keysStartIndex, numKeys, typeEnv, context);
            } else {
                typeTraits = JobGenHelper.variablesToTypeTraits(outputVars, keysStartIndex, numKeys + 1, typeEnv,
                        context);
            }

            IAsterixApplicationContextInfo appContext = (IAsterixApplicationContextInfo) context.getAppContext();
            Pair<IFileSplitProvider, AlgebricksPartitionConstraint> spPc;
            try {
                spPc = splitProviderAndPartitionConstraintsForInternalOrFeedDataset(dataset.getDataverseName(),
                        dataset.getDatasetName(), indexName);
            } catch (Exception e) {
                throw new AlgebricksException(e);
            }
            BTreeSearchOperatorDescriptor btreeSearchOp = new BTreeSearchOperatorDescriptor(jobSpec, outputRecDesc,
                    appContext.getStorageManagerInterface(), appContext.getIndexRegistryProvider(), spPc.first,
                    typeTraits, comparatorFactories, lowKeyFields, highKeyFields, lowKeyInclusive, highKeyInclusive,
                    new BTreeDataflowHelperFactory(), retainInput, NoOpOperationCallbackProvider.INSTANCE);
            return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(btreeSearchOp, spPc.second);
        } catch (MetadataException me) {
            throw new AlgebricksException(me);
        }
    }

    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildRtreeRuntime(JobSpecification jobSpec,
            List<LogicalVariable> outputVars, IOperatorSchema opSchema, IVariableTypeEnvironment typeEnv,
            JobGenContext context, boolean retainInput, Dataset dataset, String indexName, int[] keyFields)
            throws AlgebricksException {
        try {
            ARecordType recType = (ARecordType) findType(dataset.getDataverseName(), dataset.getItemTypeName());
            int numPrimaryKeys = DatasetUtils.getPartitioningKeys(dataset).size();

            Index secondaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), indexName);
            if (secondaryIndex == null) {
                throw new AlgebricksException("Code generation error: no index " + indexName + " for dataset "
                        + dataset.getDatasetName());
            }
            List<String> secondaryKeyFields = secondaryIndex.getKeyFieldNames();
            int numSecondaryKeys = secondaryKeyFields.size();
            if (numSecondaryKeys != 1) {
                throw new AlgebricksException(
                        "Cannot use "
                                + numSecondaryKeys
                                + " fields as a key for the R-tree index. There can be only one field as a key for the R-tree index.");
            }
            Pair<IAType, Boolean> keyTypePair = Index.getNonNullableKeyFieldType(secondaryKeyFields.get(0), recType);
            IAType keyType = keyTypePair.first;
            if (keyType == null) {
                throw new AlgebricksException("Could not find field " + secondaryKeyFields.get(0) + " in the schema.");
            }
            int numDimensions = NonTaggedFormatUtil.getNumDimensions(keyType.getTypeTag());
            int numNestedSecondaryKeyFields = numDimensions * 2;
            IPrimitiveValueProviderFactory[] valueProviderFactories = new IPrimitiveValueProviderFactory[numNestedSecondaryKeyFields];
            for (int i = 0; i < numNestedSecondaryKeyFields; i++) {
                valueProviderFactories[i] = AqlPrimitiveValueProviderFactory.INSTANCE;
            }

            RecordDescriptor outputRecDesc = JobGenHelper.mkRecordDescriptor(typeEnv, opSchema, context);
            int keysStartIndex = outputRecDesc.getFieldCount() - numNestedSecondaryKeyFields - numPrimaryKeys;
            if (retainInput) {
                keysStartIndex -= numNestedSecondaryKeyFields;
            }
            IBinaryComparatorFactory[] comparatorFactories = JobGenHelper.variablesToAscBinaryComparatorFactories(
                    outputVars, keysStartIndex, numNestedSecondaryKeyFields, typeEnv, context);
            ITypeTraits[] typeTraits = JobGenHelper.variablesToTypeTraits(outputVars, keysStartIndex,
                    numNestedSecondaryKeyFields, typeEnv, context);
            IAsterixApplicationContextInfo appContext = (IAsterixApplicationContextInfo) context.getAppContext();
            Pair<IFileSplitProvider, AlgebricksPartitionConstraint> spPc = splitProviderAndPartitionConstraintsForInternalOrFeedDataset(
                    dataset.getDataverseName(), dataset.getDatasetName(), indexName);
            RTreeSearchOperatorDescriptor rtreeSearchOp = new RTreeSearchOperatorDescriptor(jobSpec, outputRecDesc,
                    appContext.getStorageManagerInterface(), appContext.getIndexRegistryProvider(), spPc.first,
                    typeTraits, comparatorFactories, keyFields, new RTreeDataflowHelperFactory(valueProviderFactories),
                    retainInput, NoOpOperationCallbackProvider.INSTANCE);
            return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(rtreeSearchOp, spPc.second);
        } catch (MetadataException me) {
            throw new AlgebricksException(me);
        }
    }

    @Override
    public Pair<IPushRuntimeFactory, AlgebricksPartitionConstraint> getWriteFileRuntime(IDataSink sink,
            int[] printColumns, IPrinterFactory[] printerFactories, RecordDescriptor inputDesc) {
        FileSplitDataSink fsds = (FileSplitDataSink) sink;
        FileSplitSinkId fssi = (FileSplitSinkId) fsds.getId();
        FileSplit fs = fssi.getFileSplit();
        File outFile = fs.getLocalFile().getFile();
        String nodeId = fs.getNodeName();

        SinkWriterRuntimeFactory runtime = new SinkWriterRuntimeFactory(printColumns, printerFactories, outFile,
                getWriterFactory(), inputDesc);
        AlgebricksPartitionConstraint apc = new AlgebricksAbsolutePartitionConstraint(new String[] { nodeId });
        return new Pair<IPushRuntimeFactory, AlgebricksPartitionConstraint>(runtime, apc);
    }

    @Override
    public IDataSourceIndex<String, AqlSourceId> findDataSourceIndex(String indexId, AqlSourceId dataSourceId)
            throws AlgebricksException {
        AqlDataSource ads = findDataSource(dataSourceId);
        Dataset dataset = ads.getDataset();
        if (dataset.getDatasetType() == DatasetType.EXTERNAL) {
            throw new AlgebricksException("No index for external dataset " + dataSourceId);
        }
        try {
            String indexName = (String) indexId;
            Index secondaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), indexName);
            if (secondaryIndex != null) {
                return new AqlIndex(secondaryIndex, dataset.getDataverseName(), dataset.getDatasetName(), this);
            } else {
                Index primaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                        dataset.getDatasetName(), dataset.getDatasetName());
                if (primaryIndex.getIndexName().equals(indexId)) {
                    return new AqlIndex(primaryIndex, dataset.getDataverseName(), dataset.getDatasetName(), this);
                } else {
                    return null;
                }
            }
        } catch (MetadataException me) {
            throw new AlgebricksException(me);
        }
    }

    public AqlDataSource lookupSourceInMetadata(AqlSourceId aqlId) throws AlgebricksException, MetadataException {
        Dataset dataset = findDataset(aqlId.getDataverseName(), aqlId.getDatasetName());
        if (dataset == null) {
            throw new AlgebricksException("Datasource with id " + aqlId + " was not found.");
        }
        String tName = dataset.getItemTypeName();
        IAType itemType = MetadataManager.INSTANCE.getDatatype(mdTxnCtx, aqlId.getDataverseName(), tName).getDatatype();
        return new AqlDataSource(aqlId, dataset, itemType);
    }

    @Override
    public boolean scannerOperatorIsLeaf(IDataSource<AqlSourceId> dataSource) {
        AqlSourceId asid = dataSource.getId();
        String dataverseName = asid.getDataverseName();
        String datasetName = asid.getDatasetName();
        Dataset dataset = null;
        try {
            dataset = MetadataManager.INSTANCE.getDataset(mdTxnCtx, dataverseName, datasetName);
        } catch (MetadataException e) {
            throw new IllegalStateException(e);
        }

        if (dataset == null) {
            throw new IllegalArgumentException("Unknown dataset " + datasetName + " in dataverse " + dataverseName);
        }
        return dataset.getDatasetType() == DatasetType.EXTERNAL;
    }

    @Override
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getWriteResultRuntime(
            IDataSource<AqlSourceId> dataSource, IOperatorSchema propagatedSchema, List<LogicalVariable> keys,
            LogicalVariable payload, JobGenContext context, JobSpecification spec) throws AlgebricksException {
        String dataverseName = dataSource.getId().getDataverseName();
        String datasetName = dataSource.getId().getDatasetName();
        int numKeys = keys.size();
        // move key fields to front
        int[] fieldPermutation = new int[numKeys + 1];
        // System.arraycopy(keys, 0, fieldPermutation, 0, numKeys);
        int i = 0;
        for (LogicalVariable varKey : keys) {
            int idx = propagatedSchema.findVariable(varKey);
            fieldPermutation[i] = idx;
            i++;
        }
        fieldPermutation[numKeys] = propagatedSchema.findVariable(payload);

        Dataset dataset = findDataset(dataverseName, datasetName);
        if (dataset == null) {
            throw new AlgebricksException("Unknown dataset " + datasetName + " in dataverse " + dataverseName);
        }

        try {
            Index primaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), dataset.getDatasetName());
            String indexName = primaryIndex.getIndexName();

            String itemTypeName = dataset.getItemTypeName();
            ARecordType itemType = (ARecordType) MetadataManager.INSTANCE.getDatatype(mdTxnCtx,
                    dataset.getDataverseName(), itemTypeName).getDatatype();

            ITypeTraits[] typeTraits = DatasetUtils.computeTupleTypeTraits(dataset, itemType);
            IBinaryComparatorFactory[] comparatorFactories = DatasetUtils.computeKeysBinaryComparatorFactories(dataset,
                    itemType, context.getBinaryComparatorFactoryProvider());

            Pair<IFileSplitProvider, AlgebricksPartitionConstraint> splitsAndConstraint = splitProviderAndPartitionConstraintsForInternalOrFeedDataset(
                    dataSource.getId().getDataverseName(), datasetName, indexName);
            IAsterixApplicationContextInfo appContext = (IAsterixApplicationContextInfo) context.getAppContext();
            TreeIndexBulkLoadOperatorDescriptor btreeBulkLoad = new TreeIndexBulkLoadOperatorDescriptor(spec,
                    appContext.getStorageManagerInterface(), appContext.getIndexRegistryProvider(),
                    splitsAndConstraint.first, typeTraits, comparatorFactories, fieldPermutation,
                    GlobalConfig.DEFAULT_BTREE_FILL_FACTOR, new BTreeDataflowHelperFactory(),
                    NoOpOperationCallbackProvider.INSTANCE);
            return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(btreeBulkLoad,
                    splitsAndConstraint.second);

        } catch (MetadataException me) {
            throw new AlgebricksException(me);
        }
    }

    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getInsertOrDeleteRuntime(IndexOp indexOp,
            IDataSource<AqlSourceId> dataSource, IOperatorSchema propagatedSchema, List<LogicalVariable> keys,
            LogicalVariable payload, RecordDescriptor recordDesc, JobGenContext context, JobSpecification spec)
            throws AlgebricksException {
        String datasetName = dataSource.getId().getDatasetName();
        int numKeys = keys.size();
        // Move key fields to front.
        int[] fieldPermutation = new int[numKeys + 1];
        int i = 0;
        for (LogicalVariable varKey : keys) {
            int idx = propagatedSchema.findVariable(varKey);
            fieldPermutation[i] = idx;
            i++;
        }
        fieldPermutation[numKeys] = propagatedSchema.findVariable(payload);

        Dataset dataset = findDataset(dataSource.getId().getDataverseName(), datasetName);
        if (dataset == null) {
            throw new AlgebricksException("Unknown dataset " + datasetName + " in dataverse "
                    + dataSource.getId().getDataverseName());
        }
        try {
            Index primaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), dataset.getDatasetName());
            String indexName = primaryIndex.getIndexName();

            String itemTypeName = dataset.getItemTypeName();
            ARecordType itemType = (ARecordType) MetadataManager.INSTANCE.getDatatype(mdTxnCtx,
                    dataSource.getId().getDataverseName(), itemTypeName).getDatatype();

            ITypeTraits[] typeTraits = DatasetUtils.computeTupleTypeTraits(dataset, itemType);

            IAsterixApplicationContextInfo appContext = (IAsterixApplicationContextInfo) context.getAppContext();
            IBinaryComparatorFactory[] comparatorFactories = DatasetUtils.computeKeysBinaryComparatorFactories(dataset,
                    itemType, context.getBinaryComparatorFactoryProvider());
            Pair<IFileSplitProvider, AlgebricksPartitionConstraint> splitsAndConstraint = splitProviderAndPartitionConstraintsForInternalOrFeedDataset(
                    dataSource.getId().getDataverseName(), datasetName, indexName);
            TreeIndexInsertUpdateDeleteOperatorDescriptor btreeBulkLoad = new TreeIndexInsertUpdateDeleteOperatorDescriptor(
                    spec, recordDesc, appContext.getStorageManagerInterface(), appContext.getIndexRegistryProvider(),
                    splitsAndConstraint.first, typeTraits, comparatorFactories, fieldPermutation, indexOp,
                    new BTreeDataflowHelperFactory(), null, NoOpOperationCallbackProvider.INSTANCE, jobTxnId);
            return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(btreeBulkLoad,
                    splitsAndConstraint.second);
        } catch (MetadataException me) {
            throw new AlgebricksException(me);
        }
    }

    @Override
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getInsertRuntime(
            IDataSource<AqlSourceId> dataSource, IOperatorSchema propagatedSchema, List<LogicalVariable> keys,
            LogicalVariable payload, RecordDescriptor recordDesc, JobGenContext context, JobSpecification spec)
            throws AlgebricksException {
        return getInsertOrDeleteRuntime(IndexOp.INSERT, dataSource, propagatedSchema, keys, payload, recordDesc,
                context, spec);
    }

    @Override
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getDeleteRuntime(
            IDataSource<AqlSourceId> dataSource, IOperatorSchema propagatedSchema, List<LogicalVariable> keys,
            LogicalVariable payload, RecordDescriptor recordDesc, JobGenContext context, JobSpecification spec)
            throws AlgebricksException {
        return getInsertOrDeleteRuntime(IndexOp.DELETE, dataSource, propagatedSchema, keys, payload, recordDesc,
                context, spec);
    }

    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getIndexInsertOrDeleteRuntime(IndexOp indexOp,
            IDataSourceIndex<String, AqlSourceId> dataSourceIndex, IOperatorSchema propagatedSchema,
            IOperatorSchema[] inputSchemas, IVariableTypeEnvironment typeEnv, List<LogicalVariable> primaryKeys,
            List<LogicalVariable> secondaryKeys, ILogicalExpression filterExpr, RecordDescriptor recordDesc,
            JobGenContext context, JobSpecification spec) throws AlgebricksException {
        String indexName = dataSourceIndex.getId();
        String dataverseName = dataSourceIndex.getDataSource().getId().getDataverseName();
        String datasetName = dataSourceIndex.getDataSource().getId().getDatasetName();

        Dataset dataset = findDataset(dataverseName, datasetName);
        if (dataset == null) {
            throw new AlgebricksException("Unknown dataset " + datasetName);
        }
        Index secondaryIndex;
        try {
            secondaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), indexName);
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
        AsterixTupleFilterFactory filterFactory = createTupleFilterFactory(inputSchemas, typeEnv, filterExpr, context);
        switch (secondaryIndex.getIndexType()) {
            case BTREE: {
                return getBTreeDmlRuntime(dataverseName, datasetName, indexName, propagatedSchema, primaryKeys,
                        secondaryKeys, filterFactory, recordDesc, context, spec, indexOp);
            }
            case RTREE: {
                return getRTreeDmlRuntime(dataverseName, datasetName, indexName, propagatedSchema, primaryKeys,
                        secondaryKeys, filterFactory, recordDesc, context, spec, indexOp);
            }
            default: {
                throw new AlgebricksException("Insert and delete not implemented for index type: "
                        + secondaryIndex.getIndexType());
            }
        }
    }

    @Override
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getIndexInsertRuntime(
            IDataSourceIndex<String, AqlSourceId> dataSourceIndex, IOperatorSchema propagatedSchema,
            IOperatorSchema[] inputSchemas, IVariableTypeEnvironment typeEnv, List<LogicalVariable> primaryKeys,
            List<LogicalVariable> secondaryKeys, ILogicalExpression filterExpr, RecordDescriptor recordDesc,
            JobGenContext context, JobSpecification spec) throws AlgebricksException {
        return getIndexInsertOrDeleteRuntime(IndexOp.INSERT, dataSourceIndex, propagatedSchema, inputSchemas, typeEnv,
                primaryKeys, secondaryKeys, filterExpr, recordDesc, context, spec);
    }

    @Override
    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getIndexDeleteRuntime(
            IDataSourceIndex<String, AqlSourceId> dataSourceIndex, IOperatorSchema propagatedSchema,
            IOperatorSchema[] inputSchemas, IVariableTypeEnvironment typeEnv, List<LogicalVariable> primaryKeys,
            List<LogicalVariable> secondaryKeys, ILogicalExpression filterExpr, RecordDescriptor recordDesc,
            JobGenContext context, JobSpecification spec) throws AlgebricksException {
        return getIndexInsertOrDeleteRuntime(IndexOp.DELETE, dataSourceIndex, propagatedSchema, inputSchemas, typeEnv,
                primaryKeys, secondaryKeys, filterExpr, recordDesc, context, spec);
    }

    private AsterixTupleFilterFactory createTupleFilterFactory(IOperatorSchema[] inputSchemas,
            IVariableTypeEnvironment typeEnv, ILogicalExpression filterExpr, JobGenContext context)
            throws AlgebricksException {
        // No filtering condition.
        if (filterExpr == null) {
            return null;
        }
        IExpressionRuntimeProvider expressionRuntimeProvider = context.getExpressionRuntimeProvider();
        IScalarEvaluatorFactory filterEvalFactory = expressionRuntimeProvider.createEvaluatorFactory(filterExpr,
                typeEnv, inputSchemas, context);
        return new AsterixTupleFilterFactory(filterEvalFactory, context.getBinaryBooleanInspectorFactory());
    }

    private Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getBTreeDmlRuntime(String dataverseName,
            String datasetName, String indexName, IOperatorSchema propagatedSchema, List<LogicalVariable> primaryKeys,
            List<LogicalVariable> secondaryKeys, AsterixTupleFilterFactory filterFactory, RecordDescriptor recordDesc,
            JobGenContext context, JobSpecification spec, IndexOp indexOp) throws AlgebricksException {
        int numKeys = primaryKeys.size() + secondaryKeys.size();
        // generate field permutations
        int[] fieldPermutation = new int[numKeys];
        int i = 0;
        for (LogicalVariable varKey : secondaryKeys) {
            int idx = propagatedSchema.findVariable(varKey);
            fieldPermutation[i] = idx;
            i++;
        }
        for (LogicalVariable varKey : primaryKeys) {
            int idx = propagatedSchema.findVariable(varKey);
            fieldPermutation[i] = idx;
            i++;
        }

        Dataset dataset = findDataset(dataverseName, datasetName);
        if (dataset == null) {
            throw new AlgebricksException("Unknown dataset " + datasetName + " in dataverse " + dataverseName);
        }
        String itemTypeName = dataset.getItemTypeName();
        IAType itemType;
        try {
            itemType = MetadataManager.INSTANCE.getDatatype(mdTxnCtx, dataset.getDataverseName(), itemTypeName)
                    .getDatatype();

            if (itemType.getTypeTag() != ATypeTag.RECORD) {
                throw new AlgebricksException("Only record types can be indexed.");
            }

            ARecordType recType = (ARecordType) itemType;

            // Index parameters.
            Index secondaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), indexName);

            List<String> secondaryKeyExprs = secondaryIndex.getKeyFieldNames();
            ITypeTraits[] typeTraits = new ITypeTraits[numKeys];
            IBinaryComparatorFactory[] comparatorFactories = new IBinaryComparatorFactory[numKeys];
            for (i = 0; i < secondaryKeys.size(); ++i) {
                Pair<IAType, Boolean> keyPairType = Index.getNonNullableKeyFieldType(secondaryKeyExprs.get(i)
                        .toString(), recType);
                IAType keyType = keyPairType.first;
                comparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                        keyType, true);
                typeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(keyType);
            }
            List<String> partitioningKeys = DatasetUtils.getPartitioningKeys(dataset);
            for (String partitioningKey : partitioningKeys) {
                IAType keyType = recType.getFieldType(partitioningKey);
                comparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                        keyType, true);
                typeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(keyType);
                ++i;
            }

            IAsterixApplicationContextInfo appContext = (IAsterixApplicationContextInfo) context.getAppContext();
            Pair<IFileSplitProvider, AlgebricksPartitionConstraint> splitsAndConstraint = splitProviderAndPartitionConstraintsForInternalOrFeedDataset(
                    dataverseName, datasetName, indexName);
            TreeIndexInsertUpdateDeleteOperatorDescriptor btreeInsert = new TreeIndexInsertUpdateDeleteOperatorDescriptor(
                    spec, recordDesc, appContext.getStorageManagerInterface(), appContext.getIndexRegistryProvider(),
                    splitsAndConstraint.first, typeTraits, comparatorFactories, fieldPermutation, indexOp,
                    new BTreeDataflowHelperFactory(), filterFactory, NoOpOperationCallbackProvider.INSTANCE,
                    jobTxnId);
            return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(btreeInsert,
                    splitsAndConstraint.second);
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
    }

    private Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> getRTreeDmlRuntime(String dataverseName,
            String datasetName, String indexName, IOperatorSchema propagatedSchema, List<LogicalVariable> primaryKeys,
            List<LogicalVariable> secondaryKeys, AsterixTupleFilterFactory filterFactory, RecordDescriptor recordDesc,
            JobGenContext context, JobSpecification spec, IndexOp indexOp) throws AlgebricksException {
        try {
            Dataset dataset = MetadataManager.INSTANCE.getDataset(mdTxnCtx, dataverseName, datasetName);
            String itemTypeName = dataset.getItemTypeName();
            IAType itemType = MetadataManager.INSTANCE.getDatatype(mdTxnCtx, dataverseName, itemTypeName).getDatatype();
            if (itemType.getTypeTag() != ATypeTag.RECORD) {
                throw new AlgebricksException("Only record types can be indexed.");
            }
            ARecordType recType = (ARecordType) itemType;
            Index secondaryIndex = MetadataManager.INSTANCE.getIndex(mdTxnCtx, dataset.getDataverseName(),
                    dataset.getDatasetName(), indexName);
            List<String> secondaryKeyExprs = secondaryIndex.getKeyFieldNames();
            Pair<IAType, Boolean> keyPairType = Index.getNonNullableKeyFieldType(secondaryKeyExprs.get(0), recType);
            IAType spatialType = keyPairType.first;
            int dimension = NonTaggedFormatUtil.getNumDimensions(spatialType.getTypeTag());
            int numSecondaryKeys = dimension * 2;
            int numPrimaryKeys = primaryKeys.size();
            int numKeys = numSecondaryKeys + numPrimaryKeys;
            ITypeTraits[] typeTraits = new ITypeTraits[numKeys];
            IBinaryComparatorFactory[] comparatorFactories = new IBinaryComparatorFactory[numKeys];
            int[] fieldPermutation = new int[numKeys];
            int i = 0;

            for (LogicalVariable varKey : secondaryKeys) {
                int idx = propagatedSchema.findVariable(varKey);
                fieldPermutation[i] = idx;
                i++;
            }
            for (LogicalVariable varKey : primaryKeys) {
                int idx = propagatedSchema.findVariable(varKey);
                fieldPermutation[i] = idx;
                i++;
            }
            IAType nestedKeyType = NonTaggedFormatUtil.getNestedSpatialType(spatialType.getTypeTag());
            IPrimitiveValueProviderFactory[] valueProviderFactories = new IPrimitiveValueProviderFactory[numSecondaryKeys];
            for (i = 0; i < numSecondaryKeys; i++) {
                comparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                        nestedKeyType, true);
                typeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(nestedKeyType);
                valueProviderFactories[i] = AqlPrimitiveValueProviderFactory.INSTANCE;
            }
            List<String> partitioningKeys = DatasetUtils.getPartitioningKeys(dataset);
            for (String partitioningKey : partitioningKeys) {
                IAType keyType = recType.getFieldType(partitioningKey);
                comparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                        keyType, true);
                typeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(keyType);
                ++i;
            }

            IAsterixApplicationContextInfo appContext = (IAsterixApplicationContextInfo) context.getAppContext();
            Pair<IFileSplitProvider, AlgebricksPartitionConstraint> splitsAndConstraint = splitProviderAndPartitionConstraintsForInternalOrFeedDataset(
                    dataverseName, datasetName, indexName);
            TreeIndexInsertUpdateDeleteOperatorDescriptor rtreeUpdate = new TreeIndexInsertUpdateDeleteOperatorDescriptor(
                    spec, recordDesc, appContext.getStorageManagerInterface(), appContext.getIndexRegistryProvider(),
                    splitsAndConstraint.first, typeTraits, comparatorFactories, fieldPermutation, indexOp,
                    new RTreeDataflowHelperFactory(valueProviderFactories), filterFactory,
                    NoOpOperationCallbackProvider.INSTANCE, jobTxnId);
            return new Pair<IOperatorDescriptor, AlgebricksPartitionConstraint>(rtreeUpdate, splitsAndConstraint.second);
        } catch (MetadataException me) {
            throw new AlgebricksException(me);
        }
    }

    public long getJobTxnId() {
        return jobTxnId;
    }

    public static ITreeIndexFrameFactory createBTreeNSMInteriorFrameFactory(ITypeTraits[] typeTraits) {
        return new BTreeNSMInteriorFrameFactory(new TypeAwareTupleWriterFactory(typeTraits));
    }

    @Override
    public IFunctionInfo lookupFunction(FunctionIdentifier fid) {
        return AsterixBuiltinFunctions.lookupFunction(fid);
    }

    public Pair<IFileSplitProvider, AlgebricksPartitionConstraint> splitProviderAndPartitionConstraintsForInternalOrFeedDataset(
            String dataverseName, String datasetName, String targetIdxName) throws AlgebricksException {
        FileSplit[] splits = splitsForInternalOrFeedDataset(mdTxnCtx, dataverseName, datasetName, targetIdxName);
        IFileSplitProvider splitProvider = new ConstantFileSplitProvider(splits);
        String[] loc = new String[splits.length];
        for (int p = 0; p < splits.length; p++) {
            loc[p] = splits[p].getNodeName();
        }
        AlgebricksPartitionConstraint pc = new AlgebricksAbsolutePartitionConstraint(loc);
        return new Pair<IFileSplitProvider, AlgebricksPartitionConstraint>(splitProvider, pc);
    }

    private FileSplit[] splitsForInternalOrFeedDataset(MetadataTransactionContext mdTxnCtx, String dataverseName,
            String datasetName, String targetIdxName) throws AlgebricksException {

        try {
            File relPathFile = new File(getRelativePath(dataverseName, datasetName + "_idx_" + targetIdxName));
            Dataset dataset = MetadataManager.INSTANCE.getDataset(mdTxnCtx, dataverseName, datasetName);
            if (dataset.getDatasetType() != DatasetType.INTERNAL & dataset.getDatasetType() != DatasetType.FEED) {
                throw new AlgebricksException("Not an internal or feed dataset");
            }
            InternalDatasetDetails datasetDetails = (InternalDatasetDetails) dataset.getDatasetDetails();
            List<String> nodeGroup = MetadataManager.INSTANCE.getNodegroup(mdTxnCtx, datasetDetails.getNodeGroupName())
                    .getNodeNames();
            if (nodeGroup == null) {
                throw new AlgebricksException("Couldn't find node group " + datasetDetails.getNodeGroupName());
            }

            List<FileSplit> splitArray = new ArrayList<FileSplit>();
            for (String nd : nodeGroup) {
                String[] nodeStores = stores.get(nd);
                if (nodeStores == null) {
                    LOGGER.warning("Node " + nd + " has no stores.");
                    throw new AlgebricksException("Node " + nd + " has no stores.");
                } else {
                    for (int j = 0; j < nodeStores.length; j++) {
                        File f = new File(nodeStores[j] + File.separator + relPathFile);
                        splitArray.add(new FileSplit(nd, new FileReference(f)));
                    }
                }
            }
            FileSplit[] splits = new FileSplit[splitArray.size()];
            int i = 0;
            for (FileSplit fs : splitArray) {
                splits[i++] = fs;
            }
            return splits;
        } catch (MetadataException me) {
            throw new AlgebricksException(me);
        }
    }

    private static Map<String, String> initializeAdapterFactoryMapping() {
        Map<String, String> adapterFactoryMapping = new HashMap<String, String>();
        adapterFactoryMapping.put("edu.uci.ics.asterix.external.dataset.adapter.NCFileSystemAdapter",
                "edu.uci.ics.asterix.external.adapter.factory.NCFileSystemAdapterFactory");
        adapterFactoryMapping.put("edu.uci.ics.asterix.external.dataset.adapter.HDFSAdapter",
                "edu.uci.ics.asterix.external.adapter.factory.HDFSAdapterFactory");
        adapterFactoryMapping.put("edu.uci.ics.asterix.external.dataset.adapter.PullBasedTwitterAdapter",
                "edu.uci.ics.asterix.external.dataset.adapter.PullBasedTwitterAdapterFactory");
        adapterFactoryMapping.put("edu.uci.ics.asterix.external.dataset.adapter.RSSFeedAdapter",
                "edu.uci.ics.asterix.external.dataset.adapter..RSSFeedAdapterFactory");
        adapterFactoryMapping.put("edu.uci.ics.asterix.external.dataset.adapter.CNNFeedAdapter",
                "edu.uci.ics.asterix.external.dataset.adapter.CNNFeedAdapterFactory");
        return adapterFactoryMapping;
    }

    public DatasourceAdapter getAdapter(MetadataTransactionContext mdTxnCtx, String dataverseName, String adapterName)
            throws MetadataException {
        DatasourceAdapter adapter = null;
        // search in default namespace (built-in adapter)
        adapter = MetadataManager.INSTANCE.getAdapter(mdTxnCtx, MetadataConstants.METADATA_DATAVERSE_NAME, adapterName);

        // search in dataverse (user-defined adapter)
        if (adapter == null) {
            adapter = MetadataManager.INSTANCE.getAdapter(mdTxnCtx, dataverseName, adapterName);
        }
        return adapter;
    }

    private static String getRelativePath(String dataverseName, String fileName) {
        return dataverseName + File.separator + fileName;
    }

    public Pair<IFileSplitProvider, IFileSplitProvider> getInvertedIndexFileSplitProviders(
            IFileSplitProvider splitProvider) {
        int numSplits = splitProvider.getFileSplits().length;
        FileSplit[] btreeSplits = new FileSplit[numSplits];
        FileSplit[] invListsSplits = new FileSplit[numSplits];
        for (int i = 0; i < numSplits; i++) {
            String nodeName = splitProvider.getFileSplits()[i].getNodeName();
            String path = splitProvider.getFileSplits()[i].getLocalFile().getFile().getPath();
            btreeSplits[i] = new FileSplit(nodeName, path + "_$btree");
            invListsSplits[i] = new FileSplit(nodeName, path + "_$invlists");
        }
        return new Pair<IFileSplitProvider, IFileSplitProvider>(new ConstantFileSplitProvider(btreeSplits),
                new ConstantFileSplitProvider(invListsSplits));
    }

    public Dataset findDataset(String dataverse, String dataset) throws AlgebricksException {
        try {
            return MetadataManager.INSTANCE.getDataset(mdTxnCtx, dataverse, dataset);
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
    }

    public IAType findType(String dataverse, String typeName) {
        Datatype type;
        try {
            type = MetadataManager.INSTANCE.getDatatype(mdTxnCtx, dataverse, typeName);
        } catch (Exception e) {
            throw new IllegalStateException();
        }
        if (type == null) {
            throw new IllegalStateException();
        }
        return type.getDatatype();
    }

    public List<Index> getDatasetIndexes(String dataverseName, String datasetName) throws AlgebricksException {
        try {
            return MetadataManager.INSTANCE.getDatasetIndexes(mdTxnCtx, dataverseName, datasetName);
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
    }

    public AlgebricksPartitionConstraint getClusterLocations() {
        ArrayList<String> locs = new ArrayList<String>();
        for (String k : stores.keySet()) {
            String[] nodeStores = stores.get(k);
            for (int j = 0; j < nodeStores.length; j++) {
                locs.add(k);
            }
        }
        String[] cluster = new String[locs.size()];
        cluster = locs.toArray(cluster);
        return new AlgebricksAbsolutePartitionConstraint(cluster);
    }

    public IDataFormat getFormat() {
        return FormatUtils.getDefaultFormat();
    }

}
