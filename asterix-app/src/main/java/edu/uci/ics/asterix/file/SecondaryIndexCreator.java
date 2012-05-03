package edu.uci.ics.asterix.file;

import java.io.DataOutput;
import java.util.List;

import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.common.config.DatasetConfig.IndexType;
import edu.uci.ics.asterix.common.context.AsterixStorageManagerInterface;
import edu.uci.ics.asterix.common.context.AsterixTreeRegistryProvider;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryComparatorFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlTypeTraitProvider;
import edu.uci.ics.asterix.metadata.MetadataException;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledDatasetDecl;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledMetadataDeclarations;
import edu.uci.ics.asterix.metadata.utils.DatasetUtils;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.translator.DmlTranslator.CompiledCreateIndexStatement;
import edu.uci.ics.hyracks.algebricks.core.algebra.data.ISerializerDeserializerProvider;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.OrderOperator.IOrder.OrderKind;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.base.IEvaluatorFactory;
import edu.uci.ics.hyracks.algebricks.core.api.constraints.AlgebricksPartitionConstraint;
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
import edu.uci.ics.hyracks.dataflow.std.base.AbstractOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.file.IFileSplitProvider;
import edu.uci.ics.hyracks.dataflow.std.misc.ConstantTupleSourceOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeDataflowHelperFactory;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeSearchOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.impls.NoOpOperationCallbackProvider;

@SuppressWarnings("rawtypes")
public abstract class SecondaryIndexCreator {
    protected final PhysicalOptimizationConfig physOptConf;
    
    protected int numPrimaryKeys;
    protected int numSecondaryKeys;
    protected AqlCompiledMetadataDeclarations metadata;
    protected String datasetName;
    protected ARecordType itemType;    
    protected ISerializerDeserializer payloadSerde;
    protected IFileSplitProvider primaryFileSplitProvider;
    protected AlgebricksPartitionConstraint primaryPartitionConstrant;
    protected String secondaryIndexName;
    
    // Prevent public construction.
    protected SecondaryIndexCreator(PhysicalOptimizationConfig physOptConf) {
        this.physOptConf = physOptConf;
    }

    public static SecondaryIndexCreator createIndexCreator(IndexType indexType, PhysicalOptimizationConfig physOptConf) throws AsterixException {
        switch (indexType) {
            case BTREE: {
                return new SecondaryBTreeCreator(physOptConf);
            }
            case RTREE: {
                return new SecondaryRTreeCreator(physOptConf);
            }
            case KEYWORD: {
                return new SecondaryInvertedIndexCreator(physOptConf);
            }
            default: {
                throw new AsterixException("Unknown Index Type: " + indexType);
            }
        }
    }
    
    public abstract JobSpecification createJobSpec(CompiledCreateIndexStatement createIndexStmt,
            AqlCompiledMetadataDeclarations metadata) throws AsterixException, AlgebricksException;
    
    protected void init(CompiledCreateIndexStatement createIndexStmt) throws AsterixException, AlgebricksException {
        datasetName = createIndexStmt.getDatasetName();
        secondaryIndexName = createIndexStmt.getIndexName();
        AqlCompiledDatasetDecl compiledDatasetDecl = metadata.findDataset(datasetName);
        if (compiledDatasetDecl == null) {
            throw new AsterixException("Unknown dataset " + datasetName);
        }
        if (compiledDatasetDecl.getDatasetType() == DatasetType.EXTERNAL) {
            throw new AsterixException("Cannot index an external dataset (" + datasetName + ").");
        }
        itemType = (ARecordType) metadata.findType(compiledDatasetDecl.getItemTypeName());
        payloadSerde = AqlSerializerDeserializerProvider.INSTANCE
                .getSerializerDeserializer(itemType);
        numPrimaryKeys = DatasetUtils.getPartitioningFunctions(compiledDatasetDecl).size();
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> splitProviderAndConstraint = metadata
                .splitProviderAndPartitionConstraintsForInternalOrFeedDataset(datasetName, datasetName);
        primaryFileSplitProvider = splitProviderAndConstraint.first;
        primaryPartitionConstrant = splitProviderAndConstraint.second;
    }
    
    protected AbstractOperatorDescriptor createDummyKeyProviderOp(
            JobSpecification spec) throws AsterixException, AlgebricksException {
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
        ConstantTupleSourceOperatorDescriptor keyProviderOp = new ConstantTupleSourceOperatorDescriptor(
                spec, keyRecDesc, tb.getFieldEndOffsets(), tb.getByteArray(),
                tb.getSize());
        return keyProviderOp;
    }
    
    protected BTreeSearchOperatorDescriptor createPrimaryIndexScanOp(
            JobSpecification spec, AqlCompiledMetadataDeclarations metadata,
            AqlCompiledDatasetDecl compiledDatasetDecl, ARecordType itemType,
            ISerializerDeserializer payloadSerde,
            IFileSplitProvider splitProvider) throws AlgebricksException,
            MetadataException {
        int numPrimaryKeys = DatasetUtils.getPartitioningFunctions(
                compiledDatasetDecl).size();
        ISerializerDeserializer[] primaryRecFields = new ISerializerDeserializer[numPrimaryKeys + 1];       
        ITypeTraits[] primaryTypeTraits = new ITypeTraits[numPrimaryKeys + 1];
        IBinaryComparatorFactory[] primaryComparatorFactories = new IBinaryComparatorFactory[numPrimaryKeys];       
        ISerializerDeserializerProvider serdeProvider = metadata.getFormat()
                .getSerdeProvider();
        List<Triple<IEvaluatorFactory, ScalarFunctionCallExpression, IAType>> partitioningFunctions = DatasetUtils
                .getPartitioningFunctions(compiledDatasetDecl);
        int i = 0;
        for (Triple<IEvaluatorFactory, ScalarFunctionCallExpression, IAType> evalFactoryAndType : partitioningFunctions) {
            IAType keyType = evalFactoryAndType.third;
            ISerializerDeserializer keySerde = serdeProvider
                    .getSerializerDeserializer(keyType);
            primaryRecFields[i] = keySerde;
            primaryComparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE
                    .getBinaryComparatorFactory(keyType, OrderKind.ASC);
            primaryTypeTraits[i] = AqlTypeTraitProvider.INSTANCE
                    .getTypeTrait(keyType);
            ++i;
        }
        primaryRecFields[numPrimaryKeys] = payloadSerde;
        primaryTypeTraits[numPrimaryKeys] = AqlTypeTraitProvider.INSTANCE
                .getTypeTrait(itemType);

        // -Infinity
        int[] lowKeyFields = null;
        // +Infinity
        int[] highKeyFields = null;
        RecordDescriptor primaryRecDesc = new RecordDescriptor(primaryRecFields, primaryTypeTraits);

        BTreeSearchOperatorDescriptor primarySearchOp = new BTreeSearchOperatorDescriptor(
                spec, primaryRecDesc, AsterixStorageManagerInterface.INSTANCE,
                AsterixTreeRegistryProvider.INSTANCE, splitProvider,
                primaryTypeTraits, primaryComparatorFactories, lowKeyFields,
                highKeyFields, true, true, new BTreeDataflowHelperFactory(),
                NoOpOperationCallbackProvider.INSTANCE);
        return primarySearchOp;
    }
}
