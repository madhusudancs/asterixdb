package edu.uci.ics.asterix.file;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledMetadataDeclarations;
import edu.uci.ics.asterix.translator.DmlTranslator.CompiledCreateIndexStatement;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.jobgen.impl.ConnectorPolicyAssignmentPolicy;
import edu.uci.ics.hyracks.algebricks.core.algebra.runtime.operators.meta.AlgebricksMetaOperatorDescriptor;
import edu.uci.ics.hyracks.algebricks.core.api.constraints.AlgebricksPartitionConstraintHelper;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.dataflow.std.base.AbstractOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.connectors.OneToOneConnectorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.sort.ExternalSortOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeDataflowHelperFactory;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeSearchOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.impls.BTree;
import edu.uci.ics.hyracks.storage.am.common.dataflow.TreeIndexBulkLoadOperatorDescriptor;

public class SecondaryBTreeCreator extends SecondaryIndexCreator {
    
    protected SecondaryBTreeCreator(PhysicalOptimizationConfig physOptConf) {
        super(physOptConf);
    }

    @Override
    public JobSpecification createJobSpec(CompiledCreateIndexStatement createIndexStmt,
            AqlCompiledMetadataDeclarations metadata) throws AsterixException, AlgebricksException {
        init(createIndexStmt, metadata);
        JobSpecification spec = new JobSpecification();
        
        // Create dummy key provider for feeding the primary index scan. 
        AbstractOperatorDescriptor keyProviderOp = createDummyKeyProviderOp(spec);
        
        // Create primary index scan op.
        BTreeSearchOperatorDescriptor primaryScanOp = createPrimaryIndexScanOp(spec);
        
        // Assign op.
        AlgebricksMetaOperatorDescriptor asterixAssignOp = createAssignOp(spec, primaryScanOp, numSecondaryKeys);
        
        // Sort by secondary keys.
        ExternalSortOperatorDescriptor sortOp = createSortOp(spec, secondaryComparatorFactories, secondaryRecDesc);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, sortOp, primaryPartitionConstraint);

        // Create secondary BTree bulk load op.
        TreeIndexBulkLoadOperatorDescriptor secondaryBulkLoadOp = createTreeIndexBulkLoadOp(spec, numSecondaryKeys,
                new BTreeDataflowHelperFactory(), BTree.DEFAULT_FILL_FACTOR);

        // Connect the operators.
        spec.connect(new OneToOneConnectorDescriptor(spec), keyProviderOp, 0, primaryScanOp, 0);
        spec.connect(new OneToOneConnectorDescriptor(spec), primaryScanOp, 0, asterixAssignOp, 0);
        spec.connect(new OneToOneConnectorDescriptor(spec), asterixAssignOp, 0, sortOp, 0);
        spec.connect(new OneToOneConnectorDescriptor(spec), sortOp, 0, secondaryBulkLoadOp, 0);
        spec.addRoot(secondaryBulkLoadOp);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;

    }
}
