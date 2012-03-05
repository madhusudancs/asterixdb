package edu.uci.ics.asterix.optimizer.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.mutable.Mutable;

import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalPlan;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.InnerJoinOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import edu.uci.ics.hyracks.algebricks.core.algebra.util.OperatorManipulationUtil;
import edu.uci.ics.hyracks.algebricks.core.algebra.util.OperatorPropertiesUtil;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
import edu.uci.ics.hyracks.algebricks.core.utils.Pair;



public class PushGroupByThroughProduct implements IAlgebraicRewriteRule {

    private enum PushTestResult {
        FALSE, TRUE, REPEATED_DECORS
    }

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        AbstractLogicalOperator op1 = (AbstractLogicalOperator) opRef.getValue();
        if (op1.getOperatorTag() != LogicalOperatorTag.GROUP) {
            return false;
        }
        Mutable<ILogicalOperator> opRef2 = op1.getInputs().get(0);
        AbstractLogicalOperator op2 = (AbstractLogicalOperator) opRef2.getValue();
        if (op2.getOperatorTag() != LogicalOperatorTag.INNERJOIN) {
            return false;
        }
        InnerJoinOperator join = (InnerJoinOperator) op2;
        if (!OperatorPropertiesUtil.isAlwaysTrueCond(join.getCondition().getValue())) {
            // not a product
            return false;
        }
        GroupByOperator gby = (GroupByOperator) op1;

        List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> decorToPush = new ArrayList<Pair<LogicalVariable, Mutable<ILogicalExpression>>>();
        List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> decorNotToPush = new ArrayList<Pair<LogicalVariable, Mutable<ILogicalExpression>>>();

        Mutable<ILogicalOperator> opLeftRef = join.getInputs().get(0);
        ILogicalOperator opLeft = opLeftRef.getValue();
        switch (canPushThrough(gby, opLeft, decorToPush, decorNotToPush)) {
            case REPEATED_DECORS: {
                return false;
            }
            case TRUE: {
                push(opRef, opRef2, 0, decorToPush, decorNotToPush, context);
                return true;
            }
            case FALSE: {
                decorToPush.clear();
                Mutable<ILogicalOperator> opRightRef = join.getInputs().get(1);
                ILogicalOperator opRight = opRightRef.getValue();
                if (canPushThrough(gby, opRight, decorToPush, decorNotToPush) == PushTestResult.TRUE) {
                    push(opRef, opRef2, 1, decorToPush, decorNotToPush, context);
                    return true;
                } else {
                    return false;
                }
            }
            default: {
                throw new IllegalStateException();
            }
        }
    }

    private void push(Mutable<ILogicalOperator> opRefGby, Mutable<ILogicalOperator> opRefJoin, int branch,
            List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> decorToPush,
            List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> decorNotToPush, IOptimizationContext context)
            throws AlgebricksException {
        GroupByOperator gby = (GroupByOperator) opRefGby.getValue();
        AbstractBinaryJoinOperator join = (AbstractBinaryJoinOperator) opRefJoin.getValue();
        gby.getDecorList().clear();
        gby.getDecorList().addAll(decorToPush);
        for (Pair<LogicalVariable, Mutable<ILogicalExpression>> p : decorNotToPush) {
            LogicalVariable v1 = p.first;
            VariableReferenceExpression varRef = (VariableReferenceExpression) p.second.getValue();
            LogicalVariable v2 = varRef.getVariableReference();
            OperatorManipulationUtil.substituteVarRec(join, v2, v1, true, context);
        }
        Mutable<ILogicalOperator> branchRef = join.getInputs().get(branch);
        ILogicalOperator opBranch = branchRef.getValue();
        opRefJoin.setValue(opBranch);
        branchRef.setValue(gby);
        opRefGby.setValue(join);
    }

    private PushTestResult canPushThrough(GroupByOperator gby, ILogicalOperator branch,
            List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> toPush,
            List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> notToPush) throws AlgebricksException {
        Collection<LogicalVariable> fromBranch = new HashSet<LogicalVariable>();
        VariableUtilities.getLiveVariables(branch, fromBranch);
        Collection<LogicalVariable> usedInGbyExprList = new ArrayList<LogicalVariable>();
        for (Pair<LogicalVariable, Mutable<ILogicalExpression>> p : gby.getGroupByList()) {
            p.second.getValue().getUsedVariables(usedInGbyExprList);
        }

        if (!fromBranch.containsAll(usedInGbyExprList)) {
            return PushTestResult.FALSE;
        }
        Set<LogicalVariable> free = new HashSet<LogicalVariable>();
        for (ILogicalPlan p : gby.getNestedPlans()) {
            for (Mutable<ILogicalOperator> r : p.getRoots()) {
                OperatorPropertiesUtil.getFreeVariablesInSelfOrDesc((AbstractLogicalOperator) r.getValue(), free);
            }
        }
        if (!fromBranch.containsAll(free)) {
            return PushTestResult.FALSE;
        }

        Set<LogicalVariable> decorVarRhs = new HashSet<LogicalVariable>();
        decorVarRhs.clear();
        for (Pair<LogicalVariable, Mutable<ILogicalExpression>> p : gby.getDecorList()) {
            ILogicalExpression expr = p.second.getValue();
            if (expr.getExpressionTag() != LogicalExpressionTag.VARIABLE) {
                return PushTestResult.FALSE;
            }
            VariableReferenceExpression varRef = (VariableReferenceExpression) expr;
            LogicalVariable v = varRef.getVariableReference();
            if (decorVarRhs.contains(v)) {
                return PushTestResult.REPEATED_DECORS;
            }
            decorVarRhs.add(v);

            if (fromBranch.contains(v)) {
                toPush.add(p);
            } else {
                notToPush.add(p);
            }
        }
        return PushTestResult.TRUE;
    }
}
