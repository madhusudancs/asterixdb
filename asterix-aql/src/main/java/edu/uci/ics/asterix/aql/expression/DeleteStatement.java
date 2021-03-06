package edu.uci.ics.asterix.aql.expression;

import edu.uci.ics.asterix.aql.base.Expression;
import edu.uci.ics.asterix.aql.base.Statement;
import edu.uci.ics.asterix.aql.expression.visitor.IAqlExpressionVisitor;
import edu.uci.ics.asterix.aql.expression.visitor.IAqlVisitorWithVoidReturn;
import edu.uci.ics.asterix.common.exceptions.AsterixException;

public class DeleteStatement implements Statement {

    private VariableExpr vars;
    private Identifier dataverseName;
    private Identifier datasetName;
    private Expression condition;
    private int varCounter;

    public DeleteStatement(VariableExpr vars, Identifier dataverseName, Identifier datasetName, Expression condition,
            int varCounter) {
        this.vars = vars;
        this.dataverseName = dataverseName;
        this.datasetName = datasetName;
        this.condition = condition;
        this.varCounter = varCounter;
    }

    @Override
    public Kind getKind() {
        return Kind.DELETE;
    }

    public VariableExpr getVariableExpr() {
        return vars;
    }

    public Identifier getDataverseName() {
        return dataverseName;
    }

    public Identifier getDatasetName() {
        return datasetName;
    }

    public Expression getCondition() {
        return condition;
    }

    public int getVarCounter() {
        return varCounter;
    }

    @Override
    public <R, T> R accept(IAqlExpressionVisitor<R, T> visitor, T arg) throws AsterixException {
        return visitor.visitDeleteStatement(this, arg);
    }

    @Override
    public <T> void accept(IAqlVisitorWithVoidReturn<T> visitor, T arg) throws AsterixException {
        visitor.visit(this, arg);
    }

}
