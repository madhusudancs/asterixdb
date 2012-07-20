package edu.uci.ics.asterix.om.typecomputer.impl;

import edu.uci.ics.asterix.om.typecomputer.base.IResultTypeComputer;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import edu.uci.ics.hyracks.algebricks.core.algebra.metadata.IMetadataProvider;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.exceptions.NotImplementedException;

/**
 *
 * @author Xiaoyu Ma
 */
public abstract class AbstractQuadStringTypeComputer implements IResultTypeComputer {
    
    @Override
    public IAType computeType(ILogicalExpression expression, IVariableTypeEnvironment env,
            IMetadataProvider<?, ?> metadataProvider) throws AlgebricksException {
        AbstractFunctionCallExpression fce = (AbstractFunctionCallExpression) expression;
        if(fce.getArguments().size() < 4)
            throw new AlgebricksException("Wrong Argument Number.");        
        ILogicalExpression arg0 = fce.getArguments().get(0).getValue();
        ILogicalExpression arg1 = fce.getArguments().get(1).getValue();
        ILogicalExpression arg2 = fce.getArguments().get(2).getValue();  
        ILogicalExpression arg3 = fce.getArguments().get(3).getValue();                
        IAType t0, t1, t2, t3;
        try {
            t0 = (IAType) env.getType(arg0);
            t1 = (IAType) env.getType(arg1);
            t2 = (IAType) env.getType(arg2);    
            t3 = (IAType) env.getType(arg3);              
        } catch (AlgebricksException e) {
            throw new AlgebricksException(e);
        }
        if ((t0.getTypeTag() != ATypeTag.NULL && t0.getTypeTag() != ATypeTag.STRING) || 
            (t1.getTypeTag() != ATypeTag.NULL && t1.getTypeTag() != ATypeTag.STRING) ||
            (t2.getTypeTag() != ATypeTag.NULL && t2.getTypeTag() != ATypeTag.STRING) ||
            (t3.getTypeTag() != ATypeTag.NULL && t3.getTypeTag() != ATypeTag.STRING)) {
            throw new NotImplementedException("Expects String Type.");
        }

        return getResultType(t0, t1, t2, t3);
    }    
    
    
    public abstract IAType getResultType(IAType t0, IAType t1, IAType t2, IAType t3);    
}