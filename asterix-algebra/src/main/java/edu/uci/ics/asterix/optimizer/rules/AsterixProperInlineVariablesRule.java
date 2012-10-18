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
package edu.uci.ics.asterix.optimizer.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.mutable.Mutable;

import edu.uci.ics.asterix.om.functions.AsterixBuiltinFunctions;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractLogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AssignOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import edu.uci.ics.hyracks.algebricks.core.algebra.visitors.ILogicalExpressionReferenceTransform;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

public class AsterixProperInlineVariablesRule implements IAlgebraicRewriteRule {

    // Map of variables that could be replaced by their producing expression.
    // Populated during the top-down sweep of the plan.
    private Map<LogicalVariable, ILogicalExpression> varAssignRhs = new HashMap<LogicalVariable, ILogicalExpression>();

    // Visitor for replacing variable reference expressions with their originating expression.
    private InlineVariablesVisitor inlineVisitor = new InlineVariablesVisitor(varAssignRhs);
    
    // Do not inline field accesses because doing so would interfere with our access method rewrites.
    // TODO: For now we must also exclude record constructor functions to avoid breaking our type casting rules
    // IntroduceStaticTypeCastRule and IntroduceDynamicTypeCastRule. 
    private static Set<FunctionIdentifier> doNotInlineFuncs = new HashSet<FunctionIdentifier>();
    static {
        doNotInlineFuncs.add(AsterixBuiltinFunctions.FIELD_ACCESS_BY_NAME);
        doNotInlineFuncs.add(AsterixBuiltinFunctions.FIELD_ACCESS_BY_INDEX);
        doNotInlineFuncs.add(AsterixBuiltinFunctions.CLOSED_RECORD_CONSTRUCTOR);
        doNotInlineFuncs.add(AsterixBuiltinFunctions.OPEN_RECORD_CONSTRUCTOR);
    }
    
    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        if (context.checkIfInDontApplySet(this, opRef.getValue())) {
            return false;
        }
        varAssignRhs.clear();
        inlineVisitor.setContext(context);
        boolean modified = inlineVariables(opRef, context);
        if (modified) {
            context.addToDontApplySet(this, opRef.getValue());            
        }
        return modified;
    }

    private boolean inlineVariables(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        
        // Update mapping from variables to expressions during top-down traversal.
        if (op.getOperatorTag() == LogicalOperatorTag.ASSIGN) {
            AssignOperator assignOp = (AssignOperator) op;
            List<LogicalVariable> vars = assignOp.getVariables();
            List<Mutable<ILogicalExpression>> exprs = assignOp.getExpressions();            
            for (int i = 0; i < vars.size(); i++) {
                ILogicalExpression expr = exprs.get(i).getValue();
                // Ignore functions that are in the doNotInline set.                
                if (expr.getExpressionTag() == LogicalExpressionTag.FUNCTION_CALL) {
                    AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) expr;
                    if (doNotInlineFuncs.contains(funcExpr.getFunctionIdentifier())) {
                        continue;
                    }
                }
                varAssignRhs.put(vars.get(i), exprs.get(i).getValue());
            }
        }

        // Descend into children removing projects on the way.
        boolean modified = false;
        for (Mutable<ILogicalOperator> inputOpRef : op.getInputs()) {
            if (inlineVariables(inputOpRef, context)) {
                modified = true;
            }            
        }

        // Only inline variables in operators that can deal with arbitrary expressions.
        if (!op.requiresVariableReferenceExpressions()) {
            inlineVisitor.setOperator(op);
            if (op.acceptExpressionTransform(inlineVisitor)) {
                modified = true;
            }
        }
        
        if (modified) {
            context.computeAndSetTypeEnvironmentForOperator(op);
            context.addToDontApplySet(this, op);
            // Re-enable rules that we may have already tried. They could be applicable now after inlining.
            context.removeFromAlreadyCompared(opRef.getValue());
        }

        return modified;
    }

    private class InlineVariablesVisitor implements ILogicalExpressionReferenceTransform {
        
        private final Map<LogicalVariable, ILogicalExpression> varAssignRhs;
        private final Set<LogicalVariable> liveVars = new HashSet<LogicalVariable>();
        private final List<LogicalVariable> rhsUsedVars = new ArrayList<LogicalVariable>();        
        private ILogicalOperator op;
        private IOptimizationContext context;
        
        public InlineVariablesVisitor(Map<LogicalVariable, ILogicalExpression> varAssignRhs) {
            this.varAssignRhs = varAssignRhs;
        }
        
        public void setContext(IOptimizationContext context) {
            this.context = context;
        }

        public void setOperator(ILogicalOperator op) throws AlgebricksException {
            this.op = op;
            liveVars.clear();
        }
        
        @Override
        public boolean transform(Mutable<ILogicalExpression> exprRef) throws AlgebricksException {            
            ILogicalExpression e = exprRef.getValue();
            switch (((AbstractLogicalExpression) e).getExpressionTag()) {
                case VARIABLE: {
                    // Make sure has not been excluded from inlining.
                    LogicalVariable var = ((VariableReferenceExpression) e).getVariableReference();
                    if (context.shouldNotBeInlined(var)) {
                        return false;
                    }
                    ILogicalExpression rhs = varAssignRhs.get(var);
                    if (rhs == null) {
                        // Variable was not produced by an assign.
                        return false;
                    }
                    
                    // Make sure used variables from rhs are live.
                    if (liveVars.isEmpty()) {
                        VariableUtilities.getLiveVariables(op, liveVars);
                    }
                    rhsUsedVars.clear();
                    rhs.getUsedVariables(rhsUsedVars);
                    for (LogicalVariable rhsUsedVar : rhsUsedVars) {
                        if (!liveVars.contains(rhsUsedVar)) {
                            return false;
                        }
                    }
                    
                    // Replace variable reference with a clone of the rhs expr.
                    exprRef.setValue(rhs.cloneExpression());
                    return true;
                }
                case FUNCTION_CALL: {
                    AbstractFunctionCallExpression fce = (AbstractFunctionCallExpression) e;
                    boolean modified = false;
                    for (Mutable<ILogicalExpression> arg : fce.getArguments()) {
                        if (transform(arg)) {
                            modified = true;
                        }
                    }
                    return modified;
                }
                default: {
                    return false;
                }
            }
        }
    }
}
