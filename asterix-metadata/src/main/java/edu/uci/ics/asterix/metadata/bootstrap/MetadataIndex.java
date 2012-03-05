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

package edu.uci.ics.asterix.metadata.bootstrap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import edu.uci.ics.asterix.common.exceptions.AsterixRuntimeException;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryComparatorFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryHashFunctionFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlTypeTraitProvider;
import edu.uci.ics.asterix.metadata.api.IMetadataIndex;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.runtime.transaction.TreeLogger;
import edu.uci.ics.asterix.transaction.management.exception.ACIDException;
import edu.uci.ics.asterix.transaction.management.service.logging.DataUtil;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.OrderOperator.IOrder.OrderKind;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryHashFunctionFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;

/**
 * Descriptor for a primary or secondary index on metadata datasets.
 */
public final class MetadataIndex implements IMetadataIndex {
    // Name of dataset that is indexed.
    protected final String datasetName;
    // Name of index. null for primary indexes. non-null for secondary indexes.
    protected final String indexName;
    // Types of key fields.
    protected final IAType[] keyTypes;
    // Names of key fields. Used to compute partitionExprs.
    protected final String[] keyNames;
    // Field permutation for BTree insert. Auto-created based on numFields.
    protected final int[] fieldPermutation;
    // Type of payload record for primary indexes. null for secondary indexes.
    protected final ARecordType payloadType;
    // Record descriptor of btree tuple. Created in c'tor.
    protected final RecordDescriptor recDesc;
    // Type traits of btree tuple. Created in c'tor.
    protected final ITypeTraits[] typeTraits;
    // Comparator factories for key fields of btree tuple. Created in c'tor.
    protected final IBinaryComparatorFactory[] bcfs;
    // Hash function factories for key fields of btree tuple. Created in c'tor.
    protected final IBinaryHashFunctionFactory[] bhffs;
    // Identifier of file BufferCache backing this metadata btree index.
    protected int fileId;
    // Resource id of this index for use in transactions.
    protected byte[] indexResourceId;
    // Logger for tree indexes.
    private TreeLogger treeLogger;

    public MetadataIndex(String datasetName, String indexName, int numFields, IAType[] keyTypes, String[] keyNames,
            ARecordType payloadType) throws AsterixRuntimeException {
        // Sanity checks.
        if (keyTypes.length != keyNames.length) {
            throw new AsterixRuntimeException("Unequal number of key types and names given.");
        }
        if (keyTypes.length > numFields) {
            throw new AsterixRuntimeException("Number of keys given is greater than total number of fields.");
        }
        // Set simple fields.
        this.datasetName = datasetName;
        if (indexName == null) {
            this.indexName = datasetName;
        } else {
            this.indexName = indexName;
        }
        this.keyTypes = keyTypes;
        this.keyNames = keyNames;
        this.payloadType = payloadType;
        // Create field permutation.
        fieldPermutation = new int[numFields];
        for (int i = 0; i < numFields; i++) {
            fieldPermutation[i] = i;
        }
        // Create serdes for RecordDescriptor;
        @SuppressWarnings("rawtypes")
        ISerializerDeserializer[] serdes = new ISerializerDeserializer[numFields];
        for (int i = 0; i < keyTypes.length; i++) {
            serdes[i] = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(keyTypes[i]);
        }
        // For primary indexes, add payload field serde.
        if (fieldPermutation.length > keyTypes.length) {
            serdes[numFields - 1] = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(payloadType);
        }
        recDesc = new RecordDescriptor(serdes);
        // Create type traits.
        typeTraits = new ITypeTraits[fieldPermutation.length];
        for (int i = 0; i < keyTypes.length; i++) {
            typeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(keyTypes[i]);
        }
        // For primary indexes, add payload field.
        if (fieldPermutation.length > keyTypes.length) {
            typeTraits[fieldPermutation.length - 1] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(payloadType);
        }
        // Create binary comparator factories.
        bcfs = new IBinaryComparatorFactory[keyTypes.length];
        for (int i = 0; i < keyTypes.length; i++) {
            bcfs[i] = AqlBinaryComparatorFactoryProvider.INSTANCE
                    .getBinaryComparatorFactory(keyTypes[i], OrderKind.ASC);
        }
        // Create binary hash function factories.
        bhffs = new IBinaryHashFunctionFactory[keyTypes.length];
        for (int i = 0; i < keyTypes.length; i++) {
            bhffs[i] = AqlBinaryHashFunctionFactoryProvider.INSTANCE.getBinaryHashFunctionFactory(keyTypes[i]);
        }
    }

    @Override
    public String getIndexedDatasetName() {
        return datasetName;
    }

    @Override
    public int[] getFieldPermutation() {
        return fieldPermutation;
    }

    @Override
    public int getKeyFieldCount() {
        return keyTypes.length;
    }

    @Override
    public int getFieldCount() {
        return fieldPermutation.length;
    }

    @Override
    public String getDataverseName() {
        return MetadataConstants.METADATA_DATAVERSE_NAME;
    }

    @Override
    public String getNodeGroupName() {
        return MetadataConstants.METADATA_NODEGROUP_NAME;
    }

    @Override
    public List<String> getPartitioningExpr() {
        ArrayList<String> partitioningExpr = new ArrayList<String>();
        for (int i = 0; i < keyNames.length; i++) {
            partitioningExpr.add(keyNames[i]);
        }
        return partitioningExpr;
    }

    @Override
    public String getIndexName() {
        return indexName;
    }

    @Override
    public ITypeTraits[] getTypeTraits() {
        return typeTraits;
    }

    @Override
    public RecordDescriptor getRecordDescriptor() {
        return recDesc;
    }

    @Override
    public IBinaryComparatorFactory[] getKeyBinaryComparatorFactory() {
        return bcfs;
    }

    @Override
    public IBinaryHashFunctionFactory[] getKeyBinaryHashFunctionFactory() {
        return bhffs;
    }

    @Override
    public String getFileNameRelativePath() {
        return getDataverseName() + File.separator + getIndexedDatasetName() + "_idx_" + getIndexName();
    }

    @Override
    public void setFileId(int fileId) {
        this.fileId = fileId;
        this.indexResourceId = DataUtil.intToByteArray(fileId);
    }

    @Override
    public void initTreeLogger() throws ACIDException {
        this.treeLogger = new TreeLogger(indexResourceId);
    }

    @Override
    public int getFileId() {
        return fileId;
    }

    @Override
    public ARecordType getPayloadRecordType() {
        return payloadType;
    }

    @Override
    public byte[] getResourceId() {
        return indexResourceId;
    }

    public TreeLogger getTreeLogger() {
        return treeLogger;
    }
}