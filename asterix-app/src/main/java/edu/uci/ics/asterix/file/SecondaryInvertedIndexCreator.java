package edu.uci.ics.asterix.file;

import java.io.DataOutput;
import java.util.List;

import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.common.context.AsterixStorageManagerInterface;
import edu.uci.ics.asterix.common.context.AsterixTreeRegistryProvider;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryComparatorFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryTokenizerFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlTypeTraitProvider;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledDatasetDecl;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledIndexDecl;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledMetadataDeclarations;
import edu.uci.ics.asterix.metadata.utils.DatasetUtils;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.translator.DmlTranslator.CompiledCreateIndexStatement;
import edu.uci.ics.hyracks.algebricks.core.algebra.data.ISerializerDeserializerProvider;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.OrderOperator.IOrder.OrderKind;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.base.IEvaluatorFactory;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.base.IPushRuntimeFactory;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.jobgen.impl.ConnectorPolicyAssignmentPolicy;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.operators.meta.AlgebricksMetaOperatorDescriptor;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.operators.std.AssignRuntimeFactory;
import edu.uci.ics.hyracks.algebricks.core.api.constraints.AlgebricksPartitionConstraint;
import edu.uci.ics.hyracks.algebricks.core.api.constraints.AlgebricksPartitionConstraintHelper;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;
import edu.uci.ics.hyracks.algebricks.core.utils.Pair;
import edu.uci.ics.hyracks.algebricks.core.utils.Triple;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import edu.uci.ics.hyracks.dataflow.std.connectors.OneToOneConnectorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.file.IFileSplitProvider;
import edu.uci.ics.hyracks.dataflow.std.misc.ConstantTupleSourceOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.sort.ExternalSortOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeDataflowHelperFactory;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeSearchOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.dataflow.IIndex;
import edu.uci.ics.hyracks.storage.am.common.dataflow.IIndexRegistryProvider;
import edu.uci.ics.hyracks.storage.am.common.dataflow.TreeIndexBulkLoadOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.impls.NoOpOperationCallbackProvider;
import edu.uci.ics.hyracks.storage.am.invertedindex.dataflow.BinaryTokenizerOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.invertedindex.tokenizers.IBinaryTokenizerFactory;
import edu.uci.ics.hyracks.storage.common.IStorageManagerInterface;

@SuppressWarnings("rawtypes")
public class SecondaryInvertedIndexCreator extends SecondaryIndexCreator {
    
    protected SecondaryInvertedIndexCreator(PhysicalOptimizationConfig physOptConf) {
        super(physOptConf);
    }

    @Override
    public JobSpecification createJobSpec(CompiledCreateIndexStatement createIndexStmt,
            AqlCompiledMetadataDeclarations metadata) throws AsterixException, AlgebricksException {
        JobSpecification spec = new JobSpecification();

        String primaryIndexName = createIndexStmt.getDatasetName();
        String secondaryIndexName = createIndexStmt.getIndexName();

        AqlCompiledDatasetDecl compiledDatasetDecl = metadata.findDataset(primaryIndexName);
        if (compiledDatasetDecl == null) {
            throw new AsterixException("Could not find dataset " + primaryIndexName);
        }

        if (compiledDatasetDecl.getDatasetType() == DatasetType.EXTERNAL) {
            throw new AsterixException("Cannot index an external dataset (" + primaryIndexName + ").");
        }
        ARecordType itemType = (ARecordType) metadata.findType(compiledDatasetDecl.getItemTypeName());
        ISerializerDeserializerProvider serdeProvider = metadata.getFormat().getSerdeProvider();
        ISerializerDeserializer payloadSerde = serdeProvider.getSerializerDeserializer(itemType);

        int numPrimaryKeys = DatasetUtils.getPartitioningFunctions(compiledDatasetDecl).size();

        // sanity
        if (numPrimaryKeys > 1)
            throw new AsterixException("Cannot create inverted keyword index on dataset with composite primary key.");

        // sanity
        IAType fieldsToTokenizeType = AqlCompiledIndexDecl
                .keyFieldType(createIndexStmt.getKeyFields().get(0), itemType);
        for (String fieldName : createIndexStmt.getKeyFields()) {
            IAType nextFieldToTokenizeType = AqlCompiledIndexDecl.keyFieldType(fieldName, itemType);
            if (nextFieldToTokenizeType.getTypeTag() != fieldsToTokenizeType.getTypeTag()) {
                throw new AsterixException(
                        "Cannot create inverted keyword index. Fields to tokenize must be of the same type.");
            }
        }

        // ---------- START GENERAL BTREE STUFF

        IIndexRegistryProvider<IIndex> treeRegistryProvider = AsterixTreeRegistryProvider.INSTANCE;
        IStorageManagerInterface storageManager = AsterixStorageManagerInterface.INSTANCE;

        // ---------- END GENERAL BTREE STUFF

        // ---------- START KEY PROVIDER OP

        // TODO: should actually be empty tuple source
        // build tuple containing low and high search keys
        ArrayTupleBuilder tb = new ArrayTupleBuilder(1); // just one dummy field
        DataOutput dos = tb.getDataOutput();

        try {
            tb.reset();
            IntegerSerializerDeserializer.INSTANCE.serialize(0, dos); // dummy
            // field
            tb.addFieldEndOffset();
        } catch (HyracksDataException e) {
            throw new AsterixException(e);
        }

        ISerializerDeserializer[] keyRecDescSers = { IntegerSerializerDeserializer.INSTANCE };
        RecordDescriptor keyRecDesc = new RecordDescriptor(keyRecDescSers);

        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> keyProviderSplitsAndConstraint = metadata
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(primaryIndexName, primaryIndexName);

        ConstantTupleSourceOperatorDescriptor keyProviderOp = new ConstantTupleSourceOperatorDescriptor(spec,
                keyRecDesc, tb.getFieldEndOffsets(), tb.getByteArray(), tb.getSize());
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, keyProviderOp,
                keyProviderSplitsAndConstraint.second);

        // ---------- END KEY PROVIDER OP

        // ---------- START PRIMARY INDEX SCAN

        ISerializerDeserializer[] primaryRecFields = new ISerializerDeserializer[numPrimaryKeys + 1];
        IBinaryComparatorFactory[] primaryComparatorFactories = new IBinaryComparatorFactory[numPrimaryKeys];
        ITypeTraits[] primaryTypeTraits = new ITypeTraits[numPrimaryKeys + 1];
        int i = 0;
        for (Triple<IEvaluatorFactory, ScalarFunctionCallExpression, IAType> evalFactoryAndType : DatasetUtils
                .getPartitioningFunctions(compiledDatasetDecl)) {
            IAType keyType = evalFactoryAndType.third;
            ISerializerDeserializer keySerde = serdeProvider.getSerializerDeserializer(keyType);
            primaryRecFields[i] = keySerde;
            primaryComparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                    keyType, OrderKind.ASC);
            primaryTypeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(keyType);
            ++i;
        }
        primaryRecFields[numPrimaryKeys] = payloadSerde;
        primaryTypeTraits[numPrimaryKeys] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(itemType);

        int[] lowKeyFields = null; // -infinity
        int[] highKeyFields = null; // +infinity
        RecordDescriptor primaryRecDesc = new RecordDescriptor(primaryRecFields);

        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> primarySplitsAndConstraint = metadata
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(primaryIndexName, primaryIndexName);

        BTreeSearchOperatorDescriptor primarySearchOp = new BTreeSearchOperatorDescriptor(spec, primaryRecDesc,
                storageManager, treeRegistryProvider, primarySplitsAndConstraint.first, primaryTypeTraits, primaryComparatorFactories, lowKeyFields,
                highKeyFields, true, true, new BTreeDataflowHelperFactory(), NoOpOperationCallbackProvider.INSTANCE);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, primarySearchOp,
                primarySplitsAndConstraint.second);

        // ---------- END PRIMARY INDEX SCAN

        // ---------- START ASSIGN OP

        List<String> secondaryKeyFields = createIndexStmt.getKeyFields();
        int numSecondaryKeys = secondaryKeyFields.size();
        ISerializerDeserializer[] secondaryRecFields = new ISerializerDeserializer[numPrimaryKeys + numSecondaryKeys];
        IEvaluatorFactory[] evalFactories = new IEvaluatorFactory[numSecondaryKeys];
        for (i = 0; i < numSecondaryKeys; i++) {
            evalFactories[i] = metadata.getFormat().getFieldAccessEvaluatorFactory(itemType,
                    secondaryKeyFields.get(i), numPrimaryKeys);
            IAType keyType = AqlCompiledIndexDecl.keyFieldType(secondaryKeyFields.get(i), itemType);
            ISerializerDeserializer keySerde = serdeProvider.getSerializerDeserializer(keyType);
            secondaryRecFields[i] = keySerde;
        }
        // fill in serializers and comparators for primary index fields
        for (i = 0; i < numPrimaryKeys; i++) {
            secondaryRecFields[numSecondaryKeys + i] = primaryRecFields[i];
        }
        RecordDescriptor secondaryRecDesc = new RecordDescriptor(secondaryRecFields);

        int[] outColumns = new int[numSecondaryKeys];
        int[] projectionList = new int[numSecondaryKeys + numPrimaryKeys];
        for (i = 0; i < numSecondaryKeys; i++) {
            outColumns[i] = numPrimaryKeys + i + 1;
        }
        int projCount = 0;
        for (i = 0; i < numSecondaryKeys; i++) {
            projectionList[projCount++] = numPrimaryKeys + i + 1;
        }
        for (i = 0; i < numPrimaryKeys; i++) {
            projectionList[projCount++] = i;
        }

        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> assignSplitsAndConstraint = metadata
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(primaryIndexName, primaryIndexName);

        AssignRuntimeFactory assign = new AssignRuntimeFactory(outColumns, evalFactories, projectionList);
        AlgebricksMetaOperatorDescriptor asterixAssignOp = new AlgebricksMetaOperatorDescriptor(spec, 1, 1,
                new IPushRuntimeFactory[] { assign }, new RecordDescriptor[] { secondaryRecDesc });
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, asterixAssignOp,
                assignSplitsAndConstraint.second);

        // ---------- END ASSIGN OP

        // ---------- START TOKENIZER OP

        int numTokenKeyPairFields = numPrimaryKeys + 1;

        ISerializerDeserializer[] tokenKeyPairFields = new ISerializerDeserializer[numTokenKeyPairFields];
        tokenKeyPairFields[0] = serdeProvider.getSerializerDeserializer(fieldsToTokenizeType);
        for (i = 0; i < numPrimaryKeys; i++)
            tokenKeyPairFields[i + 1] = secondaryRecFields[numSecondaryKeys + i];
        RecordDescriptor tokenKeyPairRecDesc = new RecordDescriptor(tokenKeyPairFields);

        int[] fieldsToTokenize = new int[numSecondaryKeys];
        for (i = 0; i < numSecondaryKeys; i++)
            fieldsToTokenize[i] = i;

        int[] primaryKeyFields = new int[numPrimaryKeys];
        for (i = 0; i < numPrimaryKeys; i++)
            primaryKeyFields[i] = numSecondaryKeys + i;

        IBinaryTokenizerFactory tokenizerFactory = AqlBinaryTokenizerFactoryProvider.INSTANCE
                .getTokenizerFactory(fieldsToTokenizeType);
        BinaryTokenizerOperatorDescriptor tokenizerOp = new BinaryTokenizerOperatorDescriptor(spec,
                tokenKeyPairRecDesc, tokenizerFactory, fieldsToTokenize, primaryKeyFields);
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> secondarySplitsAndConstraint = metadata
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(primaryIndexName, secondaryIndexName);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, tokenizerOp,
                secondarySplitsAndConstraint.second);

        // ---------- END TOKENIZER OP

        // ---------- START EXTERNAL SORT OP

        IBinaryComparatorFactory[] tokenKeyPairComparatorFactories = new IBinaryComparatorFactory[numTokenKeyPairFields];
        tokenKeyPairComparatorFactories[0] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                fieldsToTokenizeType, OrderKind.ASC);
        for (i = 0; i < numPrimaryKeys; i++)
            tokenKeyPairComparatorFactories[i + 1] = primaryComparatorFactories[i];

        int[] sortFields = new int[numTokenKeyPairFields]; // <token, primary
        // key a, primary
        // key b, etc.>
        for (i = 0; i < numTokenKeyPairFields; i++)
            sortFields[i] = i;

        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> sorterSplitsAndConstraint = metadata
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(primaryIndexName, primaryIndexName);

        ExternalSortOperatorDescriptor sortOp = new ExternalSortOperatorDescriptor(spec,
                physOptConf.getMaxFramesExternalSort(), sortFields, tokenKeyPairComparatorFactories,
                secondaryRecDesc);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, sortOp,
                sorterSplitsAndConstraint.second);

        // ---------- END EXTERNAL SORT OP

        // ---------- START SECONDARY INDEX BULK LOAD

        ITypeTraits[] secondaryTypeTraits = new ITypeTraits[numTokenKeyPairFields];
        secondaryTypeTraits[0] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(fieldsToTokenizeType);
        for (i = 0; i < numPrimaryKeys; i++)
            secondaryTypeTraits[i + 1] = primaryTypeTraits[i];

        int[] fieldPermutation = new int[numSecondaryKeys + numPrimaryKeys];
        for (i = 0; i < numTokenKeyPairFields; i++)
            fieldPermutation[i] = i;

        TreeIndexBulkLoadOperatorDescriptor secondaryBulkLoadOp = new TreeIndexBulkLoadOperatorDescriptor(
                spec, storageManager, treeRegistryProvider,
                secondarySplitsAndConstraint.first, secondaryTypeTraits,
                tokenKeyPairComparatorFactories, fieldPermutation, 0.7f,
                new BTreeDataflowHelperFactory(),
                NoOpOperationCallbackProvider.INSTANCE);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, secondaryBulkLoadOp,
                secondarySplitsAndConstraint.second);

        // ---------- END SECONDARY INDEX BULK LOAD

        // ---------- START CONNECT THE OPERATORS

        spec.connect(new OneToOneConnectorDescriptor(spec), keyProviderOp, 0, primarySearchOp, 0);

        spec.connect(new OneToOneConnectorDescriptor(spec), primarySearchOp, 0, asterixAssignOp, 0);

        spec.connect(new OneToOneConnectorDescriptor(spec), asterixAssignOp, 0, tokenizerOp, 0);

        spec.connect(new OneToOneConnectorDescriptor(spec), tokenizerOp, 0, sortOp, 0);

        spec.connect(new OneToOneConnectorDescriptor(spec), sortOp, 0, secondaryBulkLoadOp, 0);

        spec.addRoot(secondaryBulkLoadOp);

        // ---------- END CONNECT THE OPERATORS
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;
    }

}
