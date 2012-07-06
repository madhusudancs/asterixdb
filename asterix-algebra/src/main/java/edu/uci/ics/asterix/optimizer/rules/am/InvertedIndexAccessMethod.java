package edu.uci.ics.asterix.optimizer.rules.am;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;

import edu.uci.ics.asterix.algebra.base.LogicalOperatorDeepCopyVisitor;
import edu.uci.ics.asterix.aql.util.FunctionUtils;
import edu.uci.ics.asterix.common.config.DatasetConfig.IndexType;
import edu.uci.ics.asterix.dataflow.data.common.ListEditDistanceSearchModifierFactory;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryComparatorFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryTokenizerFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlTypeTraitProvider;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledDatasetDecl;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledIndexDecl;
import edu.uci.ics.asterix.metadata.declared.AqlCompiledIndexDecl.IndexKind;
import edu.uci.ics.asterix.om.base.AFloat;
import edu.uci.ics.asterix.om.base.AInt32;
import edu.uci.ics.asterix.om.base.ANull;
import edu.uci.ics.asterix.om.base.AString;
import edu.uci.ics.asterix.om.base.IACollection;
import edu.uci.ics.asterix.om.base.IAObject;
import edu.uci.ics.asterix.om.constants.AsterixConstantValue;
import edu.uci.ics.asterix.om.functions.AsterixBuiltinFunctions;
import edu.uci.ics.asterix.om.types.AOrderedListType;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.AUnorderedListType;
import edu.uci.ics.asterix.om.types.AbstractCollectionType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.Triple;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.Counter;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.IAlgebricksConstantValue;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator.ExecutionMode;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AssignOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.DataSourceScanOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.InnerJoinOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.ProjectOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.ReplicateOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.SelectOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.UnionAllOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.UnnestMapOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.storage.am.invertedindex.api.IInvertedIndexSearchModifierFactory;
import edu.uci.ics.hyracks.storage.am.invertedindex.searchmodifiers.ConjunctiveSearchModifierFactory;
import edu.uci.ics.hyracks.storage.am.invertedindex.searchmodifiers.EditDistanceSearchModifierFactory;
import edu.uci.ics.hyracks.storage.am.invertedindex.searchmodifiers.JaccardSearchModifierFactory;
import edu.uci.ics.hyracks.storage.am.invertedindex.tokenizers.IBinaryTokenizerFactory;

/**
 * Class for helping rewrite rules to choose and apply inverted indexes.  
 */
public class InvertedIndexAccessMethod implements IAccessMethod {

    // Enum describing the search modifier type. Used for passing info to jobgen.
    public static enum SearchModifierType {
        CONJUNCTIVE,
        JACCARD,
        EDIT_DISTANCE,
        INVALID
    }
    
    private static List<FunctionIdentifier> funcIdents = new ArrayList<FunctionIdentifier>();
    static {
        funcIdents.add(AsterixBuiltinFunctions.CONTAINS);        
        // For matching similarity-check functions. For example, similarity-jaccard-check returns a list of two items,
        // and the select condition will get the first list-item and check whether it evaluates to true. 
        funcIdents.add(AsterixBuiltinFunctions.GET_ITEM);
    }
    
    // These function identifiers are matched in this AM's analyzeFuncExprArgs(), 
    // and are not visible to the outside driver.
    private static HashSet<FunctionIdentifier> secondLevelFuncIdents = new HashSet<FunctionIdentifier>();
    static {
        secondLevelFuncIdents.add(AsterixBuiltinFunctions.SIMILARITY_JACCARD_CHECK);
        secondLevelFuncIdents.add(AsterixBuiltinFunctions.EDIT_DISTANCE_CHECK);
    }
    
    public static InvertedIndexAccessMethod INSTANCE = new InvertedIndexAccessMethod();
    
    @Override
    public List<FunctionIdentifier> getOptimizableFunctions() {
       return funcIdents;
    }

    @Override
    public boolean analyzeFuncExprArgs(AbstractFunctionCallExpression funcExpr, List<AssignOperator> assigns, AccessMethodAnalysisContext analysisCtx) {
        if (funcExpr.getFunctionIdentifier() == AsterixBuiltinFunctions.CONTAINS) {
            return AccessMethodUtils.analyzeFuncExprArgsForOneConstAndVar(funcExpr, analysisCtx);
        }
        return analyzeGetItemFuncExpr(funcExpr, assigns, analysisCtx);
    }
    
    public boolean analyzeGetItemFuncExpr(AbstractFunctionCallExpression funcExpr, List<AssignOperator> assigns, AccessMethodAnalysisContext analysisCtx) {
        if (funcExpr.getFunctionIdentifier() != AsterixBuiltinFunctions.GET_ITEM) {
            return false;
        }
        ILogicalExpression arg1 = funcExpr.getArguments().get(0).getValue();
        ILogicalExpression arg2 = funcExpr.getArguments().get(1).getValue();
        // The second arg is the item index to be accessed. It must be a constant.
        if (arg2.getExpressionTag() != LogicalExpressionTag.CONSTANT) {
            return false;
        }
        // The first arg must be a variable or a function expr.
        // If it is a variable we must track its origin in the assigns to get the original function expr.
        if (arg1.getExpressionTag() != LogicalExpressionTag.VARIABLE &&
                arg1.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
            return false;
        }
        AbstractFunctionCallExpression matchedFuncExpr = null;
        // The get-item arg is function call, directly check if it's optimizable.
        if (arg1.getExpressionTag() == LogicalExpressionTag.FUNCTION_CALL) {
            matchedFuncExpr = (AbstractFunctionCallExpression) arg1;
        }
        // The get-item arg is a variable. Search the assigns for its origination function.
        int matchedAssignIndex = -1;
        if (arg1.getExpressionTag() == LogicalExpressionTag.VARIABLE) {             
            VariableReferenceExpression varRefExpr = (VariableReferenceExpression) arg1;
            // Try to find variable ref expr in all assigns.
            for (int i = 0; i < assigns.size(); i++) {
                AssignOperator assign = assigns.get(i);
                List<LogicalVariable> assignVars = assign.getVariables();
                List<Mutable<ILogicalExpression>> assignExprs = assign.getExpressions();
                for (int j = 0; j < assignVars.size(); j++) {
                    LogicalVariable var = assignVars.get(j);
                    if (var != varRefExpr.getVariableReference()) {
                        continue;
                    }
                    // We've matched the variable in the first assign. Now analyze the originating function.
                    ILogicalExpression matchedExpr = assignExprs.get(j).getValue();
                    if (matchedExpr.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
                        return false;
                    }
                    matchedAssignIndex = i;
                    matchedFuncExpr = (AbstractFunctionCallExpression) matchedExpr;
                    break;
                }
                // We've already found a match.
                if (matchedFuncExpr != null) {
                    break;
                }
            }
        }
        // Check that the matched function is optimizable by this access method.
        if (!secondLevelFuncIdents.contains(matchedFuncExpr.getFunctionIdentifier())) {
            return false;
        }
        boolean selectMatchFound = analyzeSelectSimilarityCheckFuncExprArgs(matchedFuncExpr, assigns, matchedAssignIndex, analysisCtx);
        boolean joinMatchFound = analyzeJoinSimilarityCheckFuncExprArgs(matchedFuncExpr, assigns, matchedAssignIndex, analysisCtx);
        if (selectMatchFound || joinMatchFound) {
            return true;
        }
        return false;
    }

    private boolean analyzeJoinSimilarityCheckFuncExprArgs(AbstractFunctionCallExpression funcExpr,
            List<AssignOperator> assigns, int matchedAssignIndex, AccessMethodAnalysisContext analysisCtx) {
        // There should be exactly three arguments.
        // The last function argument is assumed to be the similarity threshold.
        IAlgebricksConstantValue constThreshVal = null;
        ILogicalExpression arg3 = funcExpr.getArguments().get(2).getValue();
        if (arg3.getExpressionTag() != LogicalExpressionTag.CONSTANT) {
            return false;
        }
        constThreshVal = ((ConstantExpression) arg3).getValue();
        ILogicalExpression arg1 = funcExpr.getArguments().get(0).getValue();
        ILogicalExpression arg2 = funcExpr.getArguments().get(1).getValue();
        // We expect arg1 and arg2 to be non-constants for a join.
        if (arg1.getExpressionTag() == LogicalExpressionTag.CONSTANT 
                || arg2.getExpressionTag() == LogicalExpressionTag.CONSTANT) {
            return false;
        }
        LogicalVariable fieldVar1 = getNonConstArgFieldVar(arg1, funcExpr, assigns, matchedAssignIndex);
        if (fieldVar1 == null) {
            return false;
        }
        LogicalVariable fieldVar2 = getNonConstArgFieldVar(arg2, funcExpr, assigns, matchedAssignIndex);
        if (fieldVar2 == null) {
            return false;
        }
        analysisCtx.matchedFuncExprs.add(new OptimizableFuncExpr(funcExpr, new LogicalVariable[] { fieldVar1, fieldVar2 }, new IAlgebricksConstantValue[] { constThreshVal }));
        return true;
    }
    
    private boolean analyzeSelectSimilarityCheckFuncExprArgs(AbstractFunctionCallExpression funcExpr,
            List<AssignOperator> assigns, int matchedAssignIndex, AccessMethodAnalysisContext analysisCtx) {
        // There should be exactly three arguments.
        // The last function argument is assumed to be the similarity threshold.
        IAlgebricksConstantValue constThreshVal = null;
        ILogicalExpression arg3 = funcExpr.getArguments().get(2).getValue();
        if (arg3.getExpressionTag() != LogicalExpressionTag.CONSTANT) {
            return false;
        }
        constThreshVal = ((ConstantExpression) arg3).getValue();
        ILogicalExpression arg1 = funcExpr.getArguments().get(0).getValue();
        ILogicalExpression arg2 = funcExpr.getArguments().get(1).getValue();
        // Determine whether one arg is constant, and the other is non-constant.
        ILogicalExpression constArg = null;
        ILogicalExpression nonConstArg = null;
        if (arg1.getExpressionTag() == LogicalExpressionTag.CONSTANT 
                && arg2.getExpressionTag() != LogicalExpressionTag.CONSTANT) { 
            constArg = arg1;
            nonConstArg = arg2;
        } else if(arg2.getExpressionTag() == LogicalExpressionTag.CONSTANT
                && arg1.getExpressionTag() != LogicalExpressionTag.CONSTANT) {
            constArg = arg2;
            nonConstArg = arg1;
        } else {
            return false;
        }
        ConstantExpression constExpr = (ConstantExpression) constArg;
        IAlgebricksConstantValue constFilterVal = constExpr.getValue();
        LogicalVariable fieldVar = getNonConstArgFieldVar(nonConstArg, funcExpr, assigns, matchedAssignIndex);
        if (fieldVar == null) {
            return false;
        }
        analysisCtx.matchedFuncExprs.add(new OptimizableFuncExpr(funcExpr, new LogicalVariable[] { fieldVar }, new IAlgebricksConstantValue[] { constFilterVal, constThreshVal }));
        return true;
    }
    
    private LogicalVariable getNonConstArgFieldVar(ILogicalExpression nonConstArg, AbstractFunctionCallExpression funcExpr,
            List<AssignOperator> assigns, int matchedAssignIndex) {
        LogicalVariable fieldVar = null;
        // Analyze nonConstArg depending on similarity function.
        if (funcExpr.getFunctionIdentifier() == AsterixBuiltinFunctions.SIMILARITY_JACCARD_CHECK) {            
            AbstractFunctionCallExpression nonConstFuncExpr = funcExpr;
            if (nonConstArg.getExpressionTag() == LogicalExpressionTag.FUNCTION_CALL) {
                nonConstFuncExpr = (AbstractFunctionCallExpression) nonConstArg;
                // TODO: Currently, we're only looking for word and gram tokens (non hashed).
                if (nonConstFuncExpr.getFunctionIdentifier() != AsterixBuiltinFunctions.WORD_TOKENS &&
                        nonConstFuncExpr.getFunctionIdentifier() != AsterixBuiltinFunctions.GRAM_TOKENS) {
                    return null;
                }
                // Find the variable that is being tokenized.
                nonConstArg = nonConstFuncExpr.getArguments().get(0).getValue();
            }
            if (nonConstArg.getExpressionTag() == LogicalExpressionTag.VARIABLE) {
                VariableReferenceExpression varExpr = (VariableReferenceExpression) nonConstArg;
                fieldVar = varExpr.getVariableReference();
                // Find expr corresponding to var in assigns below.
                for (int i = matchedAssignIndex + 1; i < assigns.size(); i++) {
                    AssignOperator assign = assigns.get(i);
                    boolean found = false;
                    for (int j = 0; j < assign.getVariables().size(); j++) {
                        if (fieldVar != assign.getVariables().get(j)) {
                            continue;
                        }
                        ILogicalExpression childExpr = assign.getExpressions().get(j).getValue();
                        if (childExpr.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
                            break;
                        }
                        AbstractFunctionCallExpression childFuncExpr = (AbstractFunctionCallExpression) childExpr;
                        // If fieldVar references the result of a tokenization, then we should remember the variable being tokenized.
                        if (childFuncExpr.getFunctionIdentifier() != AsterixBuiltinFunctions.WORD_TOKENS &&
                                childFuncExpr.getFunctionIdentifier() != AsterixBuiltinFunctions.GRAM_TOKENS) {
                            break;
                        }
                        // We expect the tokenizer's argument to be a variable, otherwise we cannot apply an index.
                        ILogicalExpression tokArgExpr = childFuncExpr.getArguments().get(0).getValue();
                        if (tokArgExpr.getExpressionTag() != LogicalExpressionTag.VARIABLE) {
                            break;
                        }
                        // Pass the variable being tokenized to the optimizable func expr.
                        VariableReferenceExpression tokArgVarExpr = (VariableReferenceExpression) tokArgExpr;
                        fieldVar = tokArgVarExpr.getVariableReference();
                        found = true;
                        break;
                    }
                    if (found) {
                        break;
                    }
                }
            }
        }
        if (funcExpr.getFunctionIdentifier() == AsterixBuiltinFunctions.EDIT_DISTANCE_CHECK) {
            if (nonConstArg.getExpressionTag() == LogicalExpressionTag.VARIABLE) {
                fieldVar = ((VariableReferenceExpression) nonConstArg).getVariableReference();                
            }
        }
        return fieldVar;
    }
    
    @Override
    public boolean matchAllIndexExprs() {
        return true;
    }

    @Override
    public boolean matchPrefixIndexExprs() {
        return false;
    }

    private ILogicalOperator createSecondaryToPrimaryPlan(OptimizableOperatorSubTree indexSubTree, OptimizableOperatorSubTree probeSubTree, 
            AqlCompiledIndexDecl chosenIndex, IOptimizableFuncExpr optFuncExpr, boolean retainInput, boolean requiresBroadcast, IOptimizationContext context) throws AlgebricksException {
        AqlCompiledDatasetDecl datasetDecl = indexSubTree.datasetDecl;
        ARecordType recordType = indexSubTree.recordType;
        DataSourceScanOperator dataSourceScan = indexSubTree.dataSourceScan;
        
        InvertedIndexJobGenParams jobGenParams = new InvertedIndexJobGenParams(chosenIndex.getIndexName(), chosenIndex.getKind(), datasetDecl.getName(), retainInput, requiresBroadcast);
        // Add function-specific args such as search modifier, and possibly a similarity threshold.
        addFunctionSpecificArgs(optFuncExpr, jobGenParams);
        // Add the type of search key from the optFuncExpr.
        addSearchKeyType(optFuncExpr, indexSubTree, context, jobGenParams);
        
        // Operator that feeds the secondary-index search.
        AbstractLogicalOperator inputOp = null;
        // Here we generate vars and funcs for assigning the secondary-index keys to be fed into the secondary-index search.
        // List of variables for the assign.
        ArrayList<LogicalVariable> keyVarList = new ArrayList<LogicalVariable>();
        // probeSubTree is null if we are dealing with a selection query, and non-null for join queries.
        if (probeSubTree == null) {
            // List of expressions for the assign.
            ArrayList<Mutable<ILogicalExpression>> keyExprList = new ArrayList<Mutable<ILogicalExpression>>();
            // Add key vars and exprs to argument list.
            addKeyVarsAndExprs(optFuncExpr, keyVarList, keyExprList, context);
            // Assign operator that sets the secondary-index search-key fields.
            inputOp = new AssignOperator(keyVarList, keyExprList);            
            // Input to this assign is the EmptyTupleSource (which the dataSourceScan also must have had as input).
            inputOp.getInputs().add(dataSourceScan.getInputs().get(0));
            inputOp.setExecutionMode(dataSourceScan.getExecutionMode());
        } else {
            // We are optimizing a join. Add the input variable to the secondaryIndexFuncArgs.
            LogicalVariable inputSearchVariable = getInputSearchVar(optFuncExpr, indexSubTree);
            keyVarList.add(inputSearchVariable);
            inputOp = (AbstractLogicalOperator) probeSubTree.root;
        }
        jobGenParams.setKeyVarList(keyVarList);
        UnnestMapOperator secondaryIndexUnnestOp = AccessMethodUtils.createSecondaryIndexUnnestMap(datasetDecl,
                recordType, chosenIndex, inputOp, jobGenParams, context, true, retainInput);
        // Generate the rest of the upstream plan which feeds the search results into the primary index.
        UnnestMapOperator primaryIndexUnnestOp = AccessMethodUtils.createPrimaryIndexUnnestMap(dataSourceScan,
                datasetDecl, recordType, secondaryIndexUnnestOp, context, true, retainInput, false);
        return primaryIndexUnnestOp;
    }
    
    /**
     * Returns the variable which acts as the input search key to a secondary
     * index that optimizes optFuncExpr by replacing rewriting indexSubTree
     * (which is the original subtree that will be replaced by the index plan).
     */
    private LogicalVariable getInputSearchVar(IOptimizableFuncExpr optFuncExpr, OptimizableOperatorSubTree indexSubTree) {
        if (optFuncExpr.getOperatorSubTree(0) == indexSubTree) {
            // If the index is on a dataset in subtree 0, then subtree 1 will feed.
            return optFuncExpr.getLogicalVar(1);
        } else {
            // If the index is on a dataset in subtree 1, then subtree 0 will feed.
            return optFuncExpr.getLogicalVar(0);
        }
    }
    
    @Override
    public boolean applySelectPlanTransformation(Mutable<ILogicalOperator> selectRef, OptimizableOperatorSubTree subTree,
            AqlCompiledIndexDecl chosenIndex, AccessMethodAnalysisContext analysisCtx, IOptimizationContext context)
            throws AlgebricksException {
        IOptimizableFuncExpr optFuncExpr = chooseOptFuncExpr(chosenIndex, analysisCtx);
        ILogicalOperator indexPlanRootOp = createSecondaryToPrimaryPlan(subTree, null, chosenIndex, optFuncExpr, false, false, context);
        // Replace the datasource scan with the new plan rooted at primaryIndexUnnestMap.
        subTree.dataSourceScanRef.setValue(indexPlanRootOp);
        return true;
    }
    
    @Override
    public boolean applyJoinPlanTransformation(Mutable<ILogicalOperator> joinRef,
            OptimizableOperatorSubTree leftSubTree, OptimizableOperatorSubTree rightSubTree,
            AqlCompiledIndexDecl chosenIndex, AccessMethodAnalysisContext analysisCtx, IOptimizationContext context)
            throws AlgebricksException {
        // Figure out if the index is applicable on the left or right side (if both, we arbitrarily prefer the left side).
        AqlCompiledDatasetDecl dataset = analysisCtx.indexDatasetMap.get(chosenIndex);
        // Determine probe and index subtrees based on chosen index.
        OptimizableOperatorSubTree indexSubTree = null;
        OptimizableOperatorSubTree probeSubTree = null;
        if (dataset.getName().equals(leftSubTree.datasetDecl.getName())) {
            indexSubTree = leftSubTree;
            probeSubTree = rightSubTree;
        } else if (dataset.getName().equals(rightSubTree.datasetDecl.getName())) {
            indexSubTree = rightSubTree;
            probeSubTree = leftSubTree;
        }
        IOptimizableFuncExpr optFuncExpr = chooseOptFuncExpr(chosenIndex, analysisCtx);
        
        // Clone the original join condition because we may have to modify it (and we also need the original).
        InnerJoinOperator join = (InnerJoinOperator) joinRef.getValue();
        ILogicalExpression joinCond = join.getCondition().getValue().cloneExpression();
        
        // Remember original live variables to make sure our new index-based plan returns exactly those vars as well.
        List<LogicalVariable> originalLiveVars = new ArrayList<LogicalVariable>();
        VariableUtilities.getLiveVariables(join, originalLiveVars);
        
        // Create "panic" (non indexed) nested-loop join path if necessary.
        Mutable<ILogicalOperator> panicJoinRef = null;
        if (optFuncExpr.getFuncExpr().getFunctionIdentifier() == AsterixBuiltinFunctions.EDIT_DISTANCE_CHECK) {
            panicJoinRef = new MutableObject<ILogicalOperator>(joinRef.getValue());
            Mutable<ILogicalOperator> newProbeRootRef = createPanicNestedLoopJoinPlan(panicJoinRef, indexSubTree, probeSubTree, optFuncExpr, chosenIndex, context);
            probeSubTree.rootRef.setValue(newProbeRootRef.getValue());
            probeSubTree.root = newProbeRootRef.getValue();
        }
        // Create regular indexed-nested loop join path.
        ILogicalOperator indexPlanRootOp = createSecondaryToPrimaryPlan(indexSubTree, probeSubTree, chosenIndex, optFuncExpr, true, true, context);        
        indexSubTree.dataSourceScanRef.setValue(indexPlanRootOp);
        
        // Change join into a select with the same condition.
        SelectOperator topSelect = new SelectOperator(new MutableObject<ILogicalExpression>(joinCond));
        topSelect.getInputs().add(indexSubTree.rootRef);
        topSelect.setExecutionMode(ExecutionMode.LOCAL);
        context.computeAndSetTypeEnvironmentForOperator(topSelect);
        
        // Add a project operator on top to guarantee that our new index-based plan returns exactly the same variables as the original plan.
        ProjectOperator projectOp = new ProjectOperator(originalLiveVars);
        projectOp.getInputs().add(new MutableObject<ILogicalOperator>(topSelect));
        projectOp.setExecutionMode(ExecutionMode.LOCAL);
        context.computeAndSetTypeEnvironmentForOperator(projectOp);
        joinRef.setValue(projectOp);
        
        // Hook up the indexed-nested loop join path with the "panic" (non indexed) nested-loop join path by putting a union all on top.
        if (panicJoinRef != null) {
            // Gather live variables from the index plan and the panic plan.
            List<LogicalVariable> indexPlanLiveVars = new ArrayList<LogicalVariable>();
        	VariableUtilities.getLiveVariables(joinRef.getValue(), indexPlanLiveVars);
        	List<LogicalVariable> panicPlanLiveVars = new ArrayList<LogicalVariable>();
        	VariableUtilities.getLiveVariables(panicJoinRef.getValue(), panicPlanLiveVars);
        	if (indexPlanLiveVars.size() != panicPlanLiveVars.size()) {
        	    throw new AlgebricksException("Unequal number of variables returned from index plan and panic plan.");
        	}
        	// Create variable mapping for union all operator.
        	List<Triple<LogicalVariable, LogicalVariable, LogicalVariable>> varMap = new ArrayList<Triple<LogicalVariable, LogicalVariable, LogicalVariable>>();
        	for (int i = 0; i < indexPlanLiveVars.size(); i++) {
        	    varMap.add(new Triple<LogicalVariable, LogicalVariable, LogicalVariable>(indexPlanLiveVars.get(i), panicPlanLiveVars.get(i), indexPlanLiveVars.get(i)));
        	}
        	UnionAllOperator unionAllOp = new UnionAllOperator(varMap);
        	unionAllOp.getInputs().add(new MutableObject<ILogicalOperator>(joinRef.getValue()));
        	unionAllOp.getInputs().add(panicJoinRef);
        	unionAllOp.setExecutionMode(ExecutionMode.PARTITIONED);
        	context.computeAndSetTypeEnvironmentForOperator(unionAllOp);
        	joinRef.setValue(unionAllOp);
        }
        return true;
    }
    
    private IOptimizableFuncExpr chooseOptFuncExpr(AqlCompiledIndexDecl chosenIndex, AccessMethodAnalysisContext analysisCtx) {
        // TODO: We can probably do something smarter here.
        // Pick the first expr optimizable by this index.
        List<Integer> indexExprs = analysisCtx.getIndexExprs(chosenIndex);
        int firstExprIndex = indexExprs.get(0);
        return analysisCtx.matchedFuncExprs.get(firstExprIndex);
    }

    private Mutable<ILogicalOperator> createPanicNestedLoopJoinPlan(Mutable<ILogicalOperator> joinRef, OptimizableOperatorSubTree indexSubTree, OptimizableOperatorSubTree probeSubTree, IOptimizableFuncExpr optFuncExpr, AqlCompiledIndexDecl chosenIndex, IOptimizationContext context) throws AlgebricksException {
        LogicalVariable inputSearchVar = getInputSearchVar(optFuncExpr, indexSubTree);
        
        // We split the plan into two "branches", and add selections on each side.
        AbstractLogicalOperator replicateOp = new ReplicateOperator(2);
        replicateOp.getInputs().add(new MutableObject<ILogicalOperator>(probeSubTree.root));
        replicateOp.setExecutionMode(ExecutionMode.PARTITIONED);
        context.computeAndSetTypeEnvironmentForOperator(replicateOp);        
        
        // Create select ops for removing tuples that are filterable and not filterable, respectively.
        IVariableTypeEnvironment topTypeEnv = context.getOutputTypeEnvironment(joinRef.getValue());
        IAType inputSearchVarType = (IAType) topTypeEnv.getVarType(inputSearchVar);
        Mutable<ILogicalOperator> isFilterableSelectOpRef = new MutableObject<ILogicalOperator>();
        Mutable<ILogicalOperator> isNotFilterableSelectOpRef = new MutableObject<ILogicalOperator>();
        createIsFilterableSelectOps(replicateOp, inputSearchVar, inputSearchVarType, optFuncExpr, chosenIndex, context,
                isFilterableSelectOpRef, isNotFilterableSelectOpRef);
        
        List<LogicalVariable> originalLiveVars = new ArrayList<LogicalVariable>();
        VariableUtilities.getLiveVariables(indexSubTree.root, originalLiveVars);
        
        // Copy the scan subtree in indexSubTree.
        Counter counter = new Counter(context.getVarCounter());
        LogicalOperatorDeepCopyVisitor deepCopyVisitor = new LogicalOperatorDeepCopyVisitor(counter);
        ILogicalOperator scanSubTree = deepCopyVisitor.deepCopy(indexSubTree.root, null);        
        context.setVarCounter(counter.get());
        //context.computeAndSetTypeEnvironmentForOperator(scanSubTree);
        
        List<LogicalVariable> copyLiveVars = new ArrayList<LogicalVariable>();
        VariableUtilities.getLiveVariables(scanSubTree, copyLiveVars);
        
		// Replace the inputs of the given join op, and replace variables in its
		// condition since we deep-copied one of the scanner subtrees which
		// changed variables. 
        InnerJoinOperator joinOp = (InnerJoinOperator) joinRef.getValue();
        // Substitute vars in the join condition due to copying of the scanSubTree.
        List<LogicalVariable> joinCondUsedVars = new ArrayList<LogicalVariable>();
        VariableUtilities.getUsedVariables(joinOp, joinCondUsedVars);
        for (int i = 0; i < joinCondUsedVars.size(); i++) {
            int ix = originalLiveVars.indexOf(joinCondUsedVars.get(i));
            if (ix >= 0) {
                joinOp.getCondition().getValue().substituteVar(originalLiveVars.get(ix), copyLiveVars.get(ix));
            }        
        }    
        joinOp.getInputs().clear();
        joinOp.getInputs().add(new MutableObject<ILogicalOperator>(scanSubTree));
        // Make sure that the build input (which may be materialized causing blocking) comes from 
        // the split+select, otherwise the plan will have a deadlock.
        joinOp.getInputs().add(isNotFilterableSelectOpRef);
        context.computeAndSetTypeEnvironmentForOperator(joinOp);
        
        // Return the new root of the probeSubTree.
        return isFilterableSelectOpRef;
    }
    
    private void createIsFilterableSelectOps(ILogicalOperator inputOp, LogicalVariable inputSearchVar, IAType inputSearchVarType, IOptimizableFuncExpr optFuncExpr, AqlCompiledIndexDecl chosenIndex, IOptimizationContext context, Mutable<ILogicalOperator> isFilterableSelectOpRef, Mutable<ILogicalOperator> isNotFilterableSelectOpRef) throws AlgebricksException {        
        // Create select operator for removing tuples that are not filterable.
        // First determine the proper filter function and args based on the type of the input search var.
        ILogicalExpression isFilterableExpr = null;
        switch (inputSearchVarType.getTypeTag()) {
            case STRING: {
                List<Mutable<ILogicalExpression>> isFilterableArgs = new ArrayList<Mutable<ILogicalExpression>>(4);
                isFilterableArgs.add(new MutableObject<ILogicalExpression>(new VariableReferenceExpression(inputSearchVar)));
                // Since we are optimizing a join, the similarity threshold should be the only constant in the optimizable function expression.
                isFilterableArgs.add(new MutableObject<ILogicalExpression>(new ConstantExpression(optFuncExpr.getConstantVal(0))));
                isFilterableArgs.add(new MutableObject<ILogicalExpression>(AccessMethodUtils.createInt32Constant(chosenIndex.getGramLength())));
                // TODO: Currently usePrePost is hardcoded to be true.
                isFilterableArgs.add(new MutableObject<ILogicalExpression>(AccessMethodUtils.createBooleanConstant(true)));
                isFilterableExpr = new ScalarFunctionCallExpression(FunctionUtils.getFunctionInfo(AsterixBuiltinFunctions.EDIT_DISTANCE_STRING_IS_FILTERABLE), isFilterableArgs);        
                break;
            }
            case UNORDEREDLIST:
            case ORDEREDLIST: {
                List<Mutable<ILogicalExpression>> isFilterableArgs = new ArrayList<Mutable<ILogicalExpression>>(2);
                isFilterableArgs.add(new MutableObject<ILogicalExpression>(new VariableReferenceExpression(inputSearchVar)));
                // Since we are optimizing a join, the similarity threshold should be the only constant in the optimizable function expression.
                isFilterableArgs.add(new MutableObject<ILogicalExpression>(new ConstantExpression(optFuncExpr.getConstantVal(0))));
                isFilterableExpr = new ScalarFunctionCallExpression(FunctionUtils.getFunctionInfo(AsterixBuiltinFunctions.EDIT_DISTANCE_LIST_IS_FILTERABLE), isFilterableArgs);        
                break;
            }
            default: {
            }
        }
        SelectOperator isFilterableSelectOp = new SelectOperator(new MutableObject<ILogicalExpression>(isFilterableExpr));
        isFilterableSelectOp.getInputs().add(new MutableObject<ILogicalOperator>(inputOp));
        isFilterableSelectOp.setExecutionMode(ExecutionMode.LOCAL);
        context.computeAndSetTypeEnvironmentForOperator(isFilterableSelectOp);
        
        // Select operator for removing tuples that are filterable.
        List<Mutable<ILogicalExpression>> isNotFilterableArgs = new ArrayList<Mutable<ILogicalExpression>>();
        isNotFilterableArgs.add(new MutableObject<ILogicalExpression>(isFilterableExpr));
        ILogicalExpression isNotFilterableExpr = new ScalarFunctionCallExpression(FunctionUtils.getFunctionInfo(AsterixBuiltinFunctions.NOT), isNotFilterableArgs);
        SelectOperator isNotFilterableSelectOp = new SelectOperator(new MutableObject<ILogicalExpression>(isNotFilterableExpr));
        isNotFilterableSelectOp.getInputs().add(new MutableObject<ILogicalOperator>(inputOp));
        isNotFilterableSelectOp.setExecutionMode(ExecutionMode.LOCAL);
        context.computeAndSetTypeEnvironmentForOperator(isNotFilterableSelectOp);
        
        isFilterableSelectOpRef.setValue(isFilterableSelectOp);
        isNotFilterableSelectOpRef.setValue(isNotFilterableSelectOp);
    }
    
    private void addSearchKeyType(IOptimizableFuncExpr optFuncExpr, OptimizableOperatorSubTree indexSubTree, IOptimizationContext context, InvertedIndexJobGenParams jobGenParams) throws AlgebricksException {
        // If we have two variables in the optFunxExpr, then we are optimizing a join.
        IAType type = null;
        ATypeTag typeTag = null;
        if (optFuncExpr.getNumLogicalVars() == 2) {            
            // Find the type of the variable that is going to feed into the index search.
            if (optFuncExpr.getOperatorSubTree(0) == indexSubTree) {
                // If the index is on a dataset in subtree 0, then subtree 1 will feed.
                type = (IAType) context.getOutputTypeEnvironment(optFuncExpr.getOperatorSubTree(1).root).getVarType(optFuncExpr.getLogicalVar(1));
            } else {
                // If the index is on a dataset in subtree 1, then subtree 0 will feed.
                type = (IAType) context.getOutputTypeEnvironment(optFuncExpr.getOperatorSubTree(0).root).getVarType(optFuncExpr.getLogicalVar(0));
            }
            typeTag = type.getTypeTag();
        } else { 
            // We are optimizing a selection query. Add the type of the search key constant.
            AsterixConstantValue constVal = (AsterixConstantValue) optFuncExpr.getConstantVal(0);
            IAObject obj = constVal.getObject();
            type = obj.getType();
            typeTag = type.getTypeTag();
            if (typeTag != ATypeTag.ORDEREDLIST && typeTag != ATypeTag.STRING) {
                throw new AlgebricksException("Only ordered lists and string types supported.");
            }
        }
        jobGenParams.setSearchKeyType(typeTag);
    }
    
    private void addFunctionSpecificArgs(IOptimizableFuncExpr optFuncExpr, InvertedIndexJobGenParams jobGenParams) {
        if (optFuncExpr.getFuncExpr().getFunctionIdentifier() == AsterixBuiltinFunctions.CONTAINS) {
            jobGenParams.setSearchModifierType(SearchModifierType.CONJUNCTIVE);
            jobGenParams.setSimilarityThreshold(new AsterixConstantValue(ANull.NULL));
        }
        if (optFuncExpr.getFuncExpr().getFunctionIdentifier() == AsterixBuiltinFunctions.SIMILARITY_JACCARD_CHECK) {
            jobGenParams.setSearchModifierType(SearchModifierType.JACCARD);
            // Add the similarity threshold which, by convention, is the last constant value.
            jobGenParams.setSimilarityThreshold(optFuncExpr.getConstantVal(optFuncExpr.getNumConstantVals() - 1));
        }
        if (optFuncExpr.getFuncExpr().getFunctionIdentifier() == AsterixBuiltinFunctions.EDIT_DISTANCE_CHECK) {
            jobGenParams.setSearchModifierType(SearchModifierType.EDIT_DISTANCE);
            // Add the similarity threshold which, by convention, is the last constant value.
            jobGenParams.setSimilarityThreshold(optFuncExpr.getConstantVal(optFuncExpr.getNumConstantVals() - 1));
        }
    }

    private void addKeyVarsAndExprs(IOptimizableFuncExpr optFuncExpr, ArrayList<LogicalVariable> keyVarList, ArrayList<Mutable<ILogicalExpression>> keyExprList, IOptimizationContext context) throws AlgebricksException {
        // For now we are assuming a single secondary index key.
        // Add a variable and its expr to the lists which will be passed into an assign op.
        LogicalVariable keyVar = context.newVar();
        keyVarList.add(keyVar);
        keyExprList.add(new MutableObject<ILogicalExpression>(new ConstantExpression(optFuncExpr.getConstantVal(0))));        
        return;
    }

    @Override
    public boolean exprIsOptimizable(AqlCompiledIndexDecl index, IOptimizableFuncExpr optFuncExpr) {
        if (optFuncExpr.getFuncExpr().getFunctionIdentifier() == AsterixBuiltinFunctions.EDIT_DISTANCE_CHECK) {
            // Must be for a join query.
            if (optFuncExpr.getNumConstantVals() == 1) {
                return true;
            }
            // Check for panic in selection query.
            // TODO: Panic also depends on prePost which is currently hardcoded to be true.
            AsterixConstantValue listOrStrConstVal = (AsterixConstantValue) optFuncExpr.getConstantVal(0);
            AsterixConstantValue intConstVal = (AsterixConstantValue) optFuncExpr.getConstantVal(1);
            IAObject listOrStrObj = listOrStrConstVal.getObject();
            IAObject intObj = intConstVal.getObject();
            AInt32 edThresh = (AInt32) intObj;
            int mergeThreshold = 0;
            // We can only optimize edit distance on strings using an ngram index.
            if (listOrStrObj.getType().getTypeTag() == ATypeTag.STRING && index.getKind() == IndexKind.NGRAM_INVIX) {
                AString astr = (AString) listOrStrObj;                    
                // Compute merge threshold.
                mergeThreshold = (astr.getStringValue().length() + index.getGramLength() - 1)
                        - edThresh.getIntegerValue() * index.getGramLength();
            }
            // We can only optimize edit distance on lists using a word index.
            if ((listOrStrObj.getType().getTypeTag() == ATypeTag.ORDEREDLIST || listOrStrObj.getType().getTypeTag() == ATypeTag.UNORDEREDLIST)
                    && index.getKind() == IndexKind.WORD_INVIX) {
                IACollection alist = (IACollection) listOrStrObj;        
                // Compute merge threshold.
                mergeThreshold = alist.size() - edThresh.getIntegerValue();
            }
            if (mergeThreshold <= 0) {
                // We cannot use index to optimize expr.
                return false;
            }
            return true;
        }
        // TODO: We need more checking: gram length, prePost, etc.
        if (optFuncExpr.getFuncExpr().getFunctionIdentifier() == AsterixBuiltinFunctions.SIMILARITY_JACCARD_CHECK) {
            // Check the tokenization function of the non-constant func arg to see if it fits the concrete index type.
            ILogicalExpression arg1 = optFuncExpr.getFuncExpr().getArguments().get(0).getValue();
            ILogicalExpression arg2 = optFuncExpr.getFuncExpr().getArguments().get(1).getValue();
            ILogicalExpression nonConstArg = null;
            if (arg1.getExpressionTag() != LogicalExpressionTag.CONSTANT) {
                nonConstArg = arg1;
            } else {
                nonConstArg = arg2;
            }
            if (nonConstArg.getExpressionTag() == LogicalExpressionTag.FUNCTION_CALL) {
                AbstractFunctionCallExpression nonConstfuncExpr = (AbstractFunctionCallExpression) nonConstArg;
                // We can use this index if the tokenization function matches the index type.
                if (nonConstfuncExpr.getFunctionIdentifier() == AsterixBuiltinFunctions.WORD_TOKENS &&
                        index.getKind() == IndexKind.WORD_INVIX) {
                    return true;
                }
                if (nonConstfuncExpr.getFunctionIdentifier() == AsterixBuiltinFunctions.GRAM_TOKENS &&
                        index.getKind() == IndexKind.NGRAM_INVIX) {
                    return true;
                }
            }
            // The non-constant arg is not a function call. Perhaps a variable?
            // We must have already verified during our analysis of the select condition, that this variable
            // refers to a list, or to a tokenization function.
            if (nonConstArg.getExpressionTag() == LogicalExpressionTag.VARIABLE) {
                return true;
            }
        }
        // We can only optimize contains with ngram indexes.
        if (optFuncExpr.getFuncExpr().getFunctionIdentifier() == AsterixBuiltinFunctions.CONTAINS
                && index.getKind() == IndexKind.NGRAM_INVIX) {
            // Check that the constant search string has at least gramLength characters.
            AsterixConstantValue strConstVal = (AsterixConstantValue) optFuncExpr.getConstantVal(0);
            IAObject strObj = strConstVal.getObject();
            if (strObj.getType().getTypeTag() == ATypeTag.STRING) {
                AString astr = (AString) strObj;                    
                if (astr.getStringValue().length() >= index.getGramLength()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public static IBinaryComparatorFactory getTokenBinaryComparatorFactory(IAType keyType) throws AlgebricksException {
        IAType type = keyType;
        ATypeTag typeTag = keyType.getTypeTag();        
        // Extract item type from list.
        if (typeTag == ATypeTag.UNORDEREDLIST || typeTag == ATypeTag.ORDEREDLIST) {
            AbstractCollectionType listType = (AbstractCollectionType) keyType;
            if (!listType.isTyped()) {
                throw new AlgebricksException("Cannot build an inverted index on untyped lists.)");
            }
            type = listType.getItemType();
        }
        // Ignore case for string types.
        return AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                type, true, true);
    }
    
    public static ITypeTraits getTokenTypeTrait(IAType keyType) throws AlgebricksException {
        IAType type = keyType;
        ATypeTag typeTag = keyType.getTypeTag();
        // Extract item type from list.
        if (typeTag == ATypeTag.UNORDEREDLIST) {
            AUnorderedListType ulistType = (AUnorderedListType) keyType;
            if (!ulistType.isTyped()) {
                throw new AlgebricksException("Cannot build an inverted index on untyped lists.)");
            }
            type = ulistType.getItemType();
        }
        if (typeTag == ATypeTag.ORDEREDLIST) {
            AOrderedListType olistType = (AOrderedListType) keyType;
            if (!olistType.isTyped()) {
                throw new AlgebricksException("Cannot build an inverted index on untyped lists.)");
            }
            type = olistType.getItemType();
        }
        return AqlTypeTraitProvider.INSTANCE.getTypeTrait(type);
    }
    
    public static IBinaryTokenizerFactory getBinaryTokenizerFactory(SearchModifierType searchModifierType, ATypeTag searchKeyType, AqlCompiledIndexDecl index)
            throws AlgebricksException {
        switch (index.getKind()) {
            case WORD_INVIX: {
                return AqlBinaryTokenizerFactoryProvider.INSTANCE.getWordTokenizerFactory(searchKeyType, false);
            }
            case NGRAM_INVIX: {
                // Make sure not to use pre- and postfixing for conjunctive searches.
                boolean prePost = (searchModifierType == SearchModifierType.CONJUNCTIVE) ? false : true;
                return AqlBinaryTokenizerFactoryProvider.INSTANCE.getNGramTokenizerFactory(searchKeyType,
                        index.getGramLength(), prePost, false);
            }
            default: {
                throw new AlgebricksException("Tokenizer not applicable to index kind '" + index.getKind() + "'.");
            }
        }
    }
    
    public static IBinaryTokenizerFactory getBinaryTokenizerFactory(ATypeTag keyType, IndexType indexType, int gramLength)
            throws AlgebricksException {
        switch (indexType) {
            case WORD_INVIX: {
                return AqlBinaryTokenizerFactoryProvider.INSTANCE.getWordTokenizerFactory(keyType, false);
            }
            case NGRAM_INVIX: {
                return AqlBinaryTokenizerFactoryProvider.INSTANCE.getNGramTokenizerFactory(keyType,
                        gramLength, true, false);
            }
            default: {
                throw new AlgebricksException("Tokenizer not applicable to index type '" + indexType + "'.");
            }
        }
    }
    
    public static IInvertedIndexSearchModifierFactory getSearchModifierFactory(SearchModifierType searchModifierType, IAObject simThresh, AqlCompiledIndexDecl index) throws AlgebricksException {
        switch(searchModifierType) {
            case CONJUNCTIVE: {
                return new ConjunctiveSearchModifierFactory();
            }
            case JACCARD: {
                float jaccThresh = ((AFloat) simThresh).getFloatValue();
                return new JaccardSearchModifierFactory(jaccThresh);
            }
            case EDIT_DISTANCE: {
                int edThresh = ((AInt32) simThresh).getIntegerValue();
                switch (index.getKind()) {
                    case NGRAM_INVIX: {
                        // Edit distance on strings, filtered with overlapping grams.
                        return new EditDistanceSearchModifierFactory(index.getGramLength(), edThresh);
                    }
                    case WORD_INVIX: {
                        // Edit distance on two lists. The list-elements are non-overlapping.
                        return new ListEditDistanceSearchModifierFactory(edThresh);
                    }
                    default: {
                        throw new AlgebricksException("Incompatible search modifier '" + searchModifierType + "' for index type '" + index.getKind() + "'");
                    }
                }
            }
            default: {
                throw new AlgebricksException("Unknown search modifier type '" + searchModifierType + "'.");
            }
        }
    }
}
