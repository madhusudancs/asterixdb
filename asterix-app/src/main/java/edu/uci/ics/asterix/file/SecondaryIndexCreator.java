/*
 * Copyright 2009-2013 by The Regents of the University of California
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

package edu.uci.ics.asterix.file;

import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.common.config.GlobalConfig;
import edu.uci.ics.asterix.common.context.AsterixRuntimeComponentsProvider;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryBooleanInspectorImpl;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryComparatorFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlTypeTraitProvider;
import edu.uci.ics.asterix.metadata.MetadataException;
import edu.uci.ics.asterix.metadata.dataset.hints.DatasetHints.DatasetCardinalityHint;
import edu.uci.ics.asterix.metadata.declared.AqlMetadataProvider;
import edu.uci.ics.asterix.metadata.entities.Dataset;
import edu.uci.ics.asterix.metadata.entities.Index;
import edu.uci.ics.asterix.metadata.utils.DatasetUtils;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.runtime.evaluators.functions.AndDescriptor;
import edu.uci.ics.asterix.runtime.evaluators.functions.IsNullDescriptor;
import edu.uci.ics.asterix.runtime.evaluators.functions.NotDescriptor;
import edu.uci.ics.asterix.translator.CompiledStatements.CompiledCreateIndexStatement;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraintHelper;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.Pair;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.LogicalExpressionJobGenToExpressionRuntimeProviderAdapter;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;
import edu.uci.ics.hyracks.algebricks.data.IBinaryComparatorFactoryProvider;
import edu.uci.ics.hyracks.algebricks.data.ISerializerDeserializerProvider;
import edu.uci.ics.hyracks.algebricks.data.ITypeTraitProvider;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluatorFactory;
import edu.uci.ics.hyracks.algebricks.runtime.base.IPushRuntimeFactory;
import edu.uci.ics.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import edu.uci.ics.hyracks.algebricks.runtime.evaluators.ColumnAccessEvalFactory;
import edu.uci.ics.hyracks.algebricks.runtime.operators.meta.AlgebricksMetaOperatorDescriptor;
import edu.uci.ics.hyracks.algebricks.runtime.operators.std.AssignRuntimeFactory;
import edu.uci.ics.hyracks.algebricks.runtime.operators.std.StreamSelectRuntimeFactory;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import edu.uci.ics.hyracks.dataflow.std.base.AbstractOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.file.IFileSplitProvider;
import edu.uci.ics.hyracks.dataflow.std.misc.ConstantTupleSourceOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.sort.ExternalSortOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeSearchOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.dataflow.IIndexDataflowHelperFactory;
import edu.uci.ics.hyracks.storage.am.common.dataflow.TreeIndexBulkLoadOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.impls.NoOpOperationCallbackFactory;
import edu.uci.ics.hyracks.storage.am.lsm.btree.dataflow.LSMBTreeDataflowHelperFactory;

@SuppressWarnings("rawtypes")
// TODO: We should eventually have a hierarchy of classes that can create all possible index job specs, 
// not just for creation.
public abstract class SecondaryIndexCreator {
    protected final PhysicalOptimizationConfig physOptConf;

    protected int numPrimaryKeys;
    protected int numSecondaryKeys;
    protected AqlMetadataProvider metadataProvider;
    protected String dataverseName;
    protected String datasetName;
    protected Dataset dataset;
    protected ARecordType itemType;
    protected ISerializerDeserializer payloadSerde;
    protected IFileSplitProvider primaryFileSplitProvider;
    protected AlgebricksPartitionConstraint primaryPartitionConstraint;
    protected IFileSplitProvider secondaryFileSplitProvider;
    protected AlgebricksPartitionConstraint secondaryPartitionConstraint;
    protected String secondaryIndexName;
    protected boolean anySecondaryKeyIsNullable = false;

    protected long numElementsHint;
    protected IBinaryComparatorFactory[] primaryComparatorFactories;
    protected int[] primaryBloomFilterKeyFields;
    protected RecordDescriptor primaryRecDesc;
    protected IBinaryComparatorFactory[] secondaryComparatorFactories;
    protected int[] secondaryBloomFilterKeyFields;
    protected RecordDescriptor secondaryRecDesc;
    protected ICopyEvaluatorFactory[] secondaryFieldAccessEvalFactories;

    // Prevent public construction. Should be created via createIndexCreator().
    protected SecondaryIndexCreator(PhysicalOptimizationConfig physOptConf) {
        this.physOptConf = physOptConf;
    }

    public static SecondaryIndexCreator createIndexCreator(CompiledCreateIndexStatement createIndexStmt,
            AqlMetadataProvider metadataProvider, PhysicalOptimizationConfig physOptConf) throws AsterixException,
            AlgebricksException {
        SecondaryIndexCreator indexCreator = null;
        switch (createIndexStmt.getIndexType()) {
            case BTREE: {
                indexCreator = new SecondaryBTreeCreator(physOptConf);
                break;
            }
            case RTREE: {
                indexCreator = new SecondaryRTreeCreator(physOptConf);
                break;
            }
            case WORD_INVIX:
            case NGRAM_INVIX:
            case FUZZY_WORD_INVIX:
            case FUZZY_NGRAM_INVIX: {
                indexCreator = new SecondaryInvertedIndexCreator(physOptConf);
                break;
            }
            default: {
                throw new AsterixException("Unknown Index Type: " + createIndexStmt.getIndexType());
            }
        }
        indexCreator.init(createIndexStmt, metadataProvider);
        return indexCreator;
    }

    public abstract JobSpecification buildCreationJobSpec() throws AsterixException, AlgebricksException;

    public abstract JobSpecification buildLoadingJobSpec() throws AsterixException, AlgebricksException;

    protected void init(CompiledCreateIndexStatement createIndexStmt, AqlMetadataProvider metadataProvider)
            throws AsterixException, AlgebricksException {
        this.metadataProvider = metadataProvider;
        dataverseName = createIndexStmt.getDataverseName() == null ? metadataProvider.getDefaultDataverseName()
                : createIndexStmt.getDataverseName();
        datasetName = createIndexStmt.getDatasetName();
        secondaryIndexName = createIndexStmt.getIndexName();
        dataset = metadataProvider.findDataset(dataverseName, datasetName);
        if (dataset == null) {
            throw new AsterixException("Unknown dataset " + datasetName);
        }
        if (dataset.getDatasetType() == DatasetType.EXTERNAL) {
            throw new AsterixException("Cannot index an external dataset (" + datasetName + ").");
        }
        itemType = (ARecordType) metadataProvider.findType(dataset.getDataverseName(), dataset.getItemTypeName());
        payloadSerde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(itemType);
        numPrimaryKeys = DatasetUtils.getPartitioningKeys(dataset).size();
        numSecondaryKeys = createIndexStmt.getKeyFields().size();
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> primarySplitsAndConstraint = metadataProvider
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(dataverseName, datasetName, datasetName);
        primaryFileSplitProvider = primarySplitsAndConstraint.first;
        primaryPartitionConstraint = primarySplitsAndConstraint.second;
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> secondarySplitsAndConstraint = metadataProvider
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(dataverseName, datasetName,
                        secondaryIndexName);
        secondaryFileSplitProvider = secondarySplitsAndConstraint.first;
        secondaryPartitionConstraint = secondarySplitsAndConstraint.second;
        // Must be called in this order.
        setPrimaryRecDescAndComparators();
        setSecondaryRecDescAndComparators(createIndexStmt, metadataProvider);

        String numElementsHintString = dataset.getHints().get("CARDINALITY");
        if (numElementsHintString == null) {
            numElementsHint = DatasetCardinalityHint.DEFAULT;
        } else {
            numElementsHint = Long.parseLong(dataset.getHints().get("CARDINALITY"));
        }
    }

    protected void setPrimaryRecDescAndComparators() throws AlgebricksException {
        List<String> partitioningKeys = DatasetUtils.getPartitioningKeys(dataset);
        int numPrimaryKeys = partitioningKeys.size();
        ISerializerDeserializer[] primaryRecFields = new ISerializerDeserializer[numPrimaryKeys + 1];
        ITypeTraits[] primaryTypeTraits = new ITypeTraits[numPrimaryKeys + 1];
        primaryComparatorFactories = new IBinaryComparatorFactory[numPrimaryKeys];
        primaryBloomFilterKeyFields = new int[numPrimaryKeys];
        ISerializerDeserializerProvider serdeProvider = metadataProvider.getFormat().getSerdeProvider();
        for (int i = 0; i < numPrimaryKeys; i++) {
            IAType keyType;
            try {
                keyType = itemType.getFieldType(partitioningKeys.get(i));
            } catch (IOException e) {
                throw new AlgebricksException(e);
            }
            primaryRecFields[i] = serdeProvider.getSerializerDeserializer(keyType);
            primaryComparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                    keyType, true);
            primaryTypeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(keyType);
            primaryBloomFilterKeyFields[i] = i;
        }
        primaryRecFields[numPrimaryKeys] = payloadSerde;
        primaryTypeTraits[numPrimaryKeys] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(itemType);
        primaryRecDesc = new RecordDescriptor(primaryRecFields, primaryTypeTraits);
    }

    protected void setSecondaryRecDescAndComparators(CompiledCreateIndexStatement createIndexStmt,
            AqlMetadataProvider metadataProvider) throws AlgebricksException, AsterixException {
        List<String> secondaryKeyFields = createIndexStmt.getKeyFields();
        secondaryFieldAccessEvalFactories = new ICopyEvaluatorFactory[numSecondaryKeys];
        secondaryComparatorFactories = new IBinaryComparatorFactory[numSecondaryKeys + numPrimaryKeys];
        secondaryBloomFilterKeyFields = new int[numSecondaryKeys];
        ISerializerDeserializer[] secondaryRecFields = new ISerializerDeserializer[numPrimaryKeys + numSecondaryKeys];
        ITypeTraits[] secondaryTypeTraits = new ITypeTraits[numSecondaryKeys + numPrimaryKeys];
        ISerializerDeserializerProvider serdeProvider = metadataProvider.getFormat().getSerdeProvider();
        ITypeTraitProvider typeTraitProvider = metadataProvider.getFormat().getTypeTraitProvider();
        IBinaryComparatorFactoryProvider comparatorFactoryProvider = metadataProvider.getFormat()
                .getBinaryComparatorFactoryProvider();
        for (int i = 0; i < numSecondaryKeys; i++) {
            secondaryFieldAccessEvalFactories[i] = metadataProvider.getFormat().getFieldAccessEvaluatorFactory(
                    itemType, secondaryKeyFields.get(i), numPrimaryKeys);
            Pair<IAType, Boolean> keyTypePair = Index.getNonNullableKeyFieldType(secondaryKeyFields.get(i), itemType);
            IAType keyType = keyTypePair.first;
            anySecondaryKeyIsNullable = anySecondaryKeyIsNullable || keyTypePair.second;
            ISerializerDeserializer keySerde = serdeProvider.getSerializerDeserializer(keyType);
            secondaryRecFields[i] = keySerde;
            secondaryComparatorFactories[i] = comparatorFactoryProvider.getBinaryComparatorFactory(keyType, true);
            secondaryTypeTraits[i] = typeTraitProvider.getTypeTrait(keyType);
            secondaryBloomFilterKeyFields[i] = i;
        }
        // Add serializers and comparators for primary index fields.
        for (int i = 0; i < numPrimaryKeys; i++) {
            secondaryRecFields[numSecondaryKeys + i] = primaryRecDesc.getFields()[i];
            secondaryTypeTraits[numSecondaryKeys + i] = primaryRecDesc.getTypeTraits()[i];
            secondaryComparatorFactories[numSecondaryKeys + i] = primaryComparatorFactories[i];
        }
        secondaryRecDesc = new RecordDescriptor(secondaryRecFields, secondaryTypeTraits);
    }

    protected AbstractOperatorDescriptor createDummyKeyProviderOp(JobSpecification spec) throws AsterixException,
            AlgebricksException {
        // Build dummy tuple containing one field with a dummy value inside.
        ArrayTupleBuilder tb = new ArrayTupleBuilder(1);
        DataOutput dos = tb.getDataOutput();
        tb.reset();
        try {
            // Serialize dummy value into a field.
            IntegerSerializerDeserializer.INSTANCE.serialize(0, dos);
        } catch (HyracksDataException e) {
            throw new AsterixException(e);
        }
        // Add dummy field.
        tb.addFieldEndOffset();
        ISerializerDeserializer[] keyRecDescSers = { IntegerSerializerDeserializer.INSTANCE };
        RecordDescriptor keyRecDesc = new RecordDescriptor(keyRecDescSers);
        ConstantTupleSourceOperatorDescriptor keyProviderOp = new ConstantTupleSourceOperatorDescriptor(spec,
                keyRecDesc, tb.getFieldEndOffsets(), tb.getByteArray(), tb.getSize());
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, keyProviderOp,
                primaryPartitionConstraint);
        return keyProviderOp;
    }

    protected BTreeSearchOperatorDescriptor createPrimaryIndexScanOp(JobSpecification spec) throws AlgebricksException {
        // -Infinity
        int[] lowKeyFields = null;
        // +Infinity
        int[] highKeyFields = null;
        BTreeSearchOperatorDescriptor primarySearchOp = new BTreeSearchOperatorDescriptor(spec, primaryRecDesc,
                AsterixRuntimeComponentsProvider.NOINDEX_PROVIDER, AsterixRuntimeComponentsProvider.NOINDEX_PROVIDER,
                primaryFileSplitProvider, primaryRecDesc.getTypeTraits(), primaryComparatorFactories,
                primaryBloomFilterKeyFields, lowKeyFields, highKeyFields, true, true,
                new LSMBTreeDataflowHelperFactory(AsterixRuntimeComponentsProvider.LSMBTREE_PROVIDER,
                        AsterixRuntimeComponentsProvider.LSMBTREE_PROVIDER,
                        AsterixRuntimeComponentsProvider.LSMBTREE_PROVIDER,
                        AsterixRuntimeComponentsProvider.LSMBTREE_PROVIDER, GlobalConfig.DEFAULT_INDEX_MEM_PAGE_SIZE,
                        GlobalConfig.DEFAULT_INDEX_MEM_NUM_PAGES), false, NoOpOperationCallbackFactory.INSTANCE);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, primarySearchOp,
                primaryPartitionConstraint);
        return primarySearchOp;
    }

    protected AlgebricksMetaOperatorDescriptor createAssignOp(JobSpecification spec,
            BTreeSearchOperatorDescriptor primaryScanOp, int numSecondaryKeyFields) throws AlgebricksException {
        int[] outColumns = new int[numSecondaryKeyFields];
        int[] projectionList = new int[numSecondaryKeyFields + numPrimaryKeys];
        for (int i = 0; i < numSecondaryKeyFields; i++) {
            outColumns[i] = numPrimaryKeys + i + 1;
        }
        int projCount = 0;
        for (int i = 0; i < numSecondaryKeyFields; i++) {
            projectionList[projCount++] = numPrimaryKeys + i + 1;
        }
        for (int i = 0; i < numPrimaryKeys; i++) {
            projectionList[projCount++] = i;
        }
        IScalarEvaluatorFactory[] sefs = new IScalarEvaluatorFactory[secondaryFieldAccessEvalFactories.length];
        for (int i = 0; i < secondaryFieldAccessEvalFactories.length; ++i) {
            sefs[i] = new LogicalExpressionJobGenToExpressionRuntimeProviderAdapter.ScalarEvaluatorFactoryAdapter(
                    secondaryFieldAccessEvalFactories[i]);
        }
        AssignRuntimeFactory assign = new AssignRuntimeFactory(outColumns, sefs, projectionList);
        AlgebricksMetaOperatorDescriptor asterixAssignOp = new AlgebricksMetaOperatorDescriptor(spec, 1, 1,
                new IPushRuntimeFactory[] { assign }, new RecordDescriptor[] { secondaryRecDesc });
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, asterixAssignOp,
                primaryPartitionConstraint);
        return asterixAssignOp;
    }

    protected ExternalSortOperatorDescriptor createSortOp(JobSpecification spec,
            IBinaryComparatorFactory[] secondaryComparatorFactories, RecordDescriptor secondaryRecDesc) {
        int[] sortFields = new int[secondaryComparatorFactories.length];
        for (int i = 0; i < secondaryComparatorFactories.length; i++) {
            sortFields[i] = i;
        }
        ExternalSortOperatorDescriptor sortOp = new ExternalSortOperatorDescriptor(spec,
                physOptConf.getMaxFramesExternalSort(), sortFields, secondaryComparatorFactories, secondaryRecDesc);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, sortOp, primaryPartitionConstraint);
        return sortOp;
    }

    protected TreeIndexBulkLoadOperatorDescriptor createTreeIndexBulkLoadOp(JobSpecification spec,
            int numSecondaryKeyFields, IIndexDataflowHelperFactory dataflowHelperFactory, float fillFactor)
            throws MetadataException, AlgebricksException {
        int[] fieldPermutation = new int[numSecondaryKeyFields + numPrimaryKeys];
        for (int i = 0; i < numSecondaryKeyFields + numPrimaryKeys; i++) {
            fieldPermutation[i] = i;
        }
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> secondarySplitsAndConstraint = metadataProvider
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(dataverseName, datasetName,
                        secondaryIndexName);
        TreeIndexBulkLoadOperatorDescriptor treeIndexBulkLoadOp = new TreeIndexBulkLoadOperatorDescriptor(spec,
                AsterixRuntimeComponentsProvider.LSMBTREE_PROVIDER, AsterixRuntimeComponentsProvider.LSMBTREE_PROVIDER,
                secondarySplitsAndConstraint.first, secondaryRecDesc.getTypeTraits(), secondaryComparatorFactories,
                secondaryBloomFilterKeyFields, fieldPermutation, fillFactor, false, numElementsHint,
                dataflowHelperFactory, NoOpOperationCallbackFactory.INSTANCE);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, treeIndexBulkLoadOp,
                secondarySplitsAndConstraint.second);
        return treeIndexBulkLoadOp;
    }

    public AlgebricksMetaOperatorDescriptor createFilterNullsSelectOp(JobSpecification spec, int numSecondaryKeyFields)
            throws AlgebricksException {
        ICopyEvaluatorFactory[] andArgsEvalFactories = new ICopyEvaluatorFactory[numSecondaryKeyFields];
        NotDescriptor notDesc = new NotDescriptor();
        IsNullDescriptor isNullDesc = new IsNullDescriptor();
        for (int i = 0; i < numSecondaryKeyFields; i++) {
            // Access column i, and apply 'is not null'.
            ColumnAccessEvalFactory columnAccessEvalFactory = new ColumnAccessEvalFactory(i);
            ICopyEvaluatorFactory isNullEvalFactory = isNullDesc
                    .createEvaluatorFactory(new ICopyEvaluatorFactory[] { columnAccessEvalFactory });
            ICopyEvaluatorFactory notEvalFactory = notDesc
                    .createEvaluatorFactory(new ICopyEvaluatorFactory[] { isNullEvalFactory });
            andArgsEvalFactories[i] = notEvalFactory;
        }
        ICopyEvaluatorFactory selectCond = null;
        if (numSecondaryKeyFields > 1) {
            // Create conjunctive condition where all secondary index keys must satisfy 'is not null'.
            AndDescriptor andDesc = new AndDescriptor();
            selectCond = andDesc.createEvaluatorFactory(andArgsEvalFactories);
        } else {
            selectCond = andArgsEvalFactories[0];
        }
        StreamSelectRuntimeFactory select = new StreamSelectRuntimeFactory(
                new LogicalExpressionJobGenToExpressionRuntimeProviderAdapter.ScalarEvaluatorFactoryAdapter(selectCond),
                null, AqlBinaryBooleanInspectorImpl.FACTORY);
        AlgebricksMetaOperatorDescriptor asterixSelectOp = new AlgebricksMetaOperatorDescriptor(spec, 1, 1,
                new IPushRuntimeFactory[] { select }, new RecordDescriptor[] { secondaryRecDesc });
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, asterixSelectOp,
                primaryPartitionConstraint);
        return asterixSelectOp;
    }
}
