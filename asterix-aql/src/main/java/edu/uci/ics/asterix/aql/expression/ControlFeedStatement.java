package edu.uci.ics.asterix.aql.expression;

import java.util.Map;

import edu.uci.ics.asterix.aql.base.Statement;
import edu.uci.ics.asterix.aql.expression.visitor.IAqlExpressionVisitor;
import edu.uci.ics.asterix.aql.expression.visitor.IAqlVisitorWithVoidReturn;
import edu.uci.ics.asterix.common.exceptions.AsterixException;

public class ControlFeedStatement implements Statement {

    private final Identifier dataverseName;
    private final Identifier datasetName;

    public enum OperationType {
        BEGIN,
        SUSPEND,
        RESUME,
        END,
        ALTER
    }

    private OperationType operationType;
    private Map<String, String> alterAdapterConfParams;

    public ControlFeedStatement(OperationType operation, Identifier dataverseName, Identifier datasetName) {
        this.operationType = operation;
        this.datasetName = datasetName;
        this.dataverseName = dataverseName;
    }

    public ControlFeedStatement(OperationType operation, Identifier dataverseName, Identifier datasetName,
            Map<String, String> alterAdapterConfParams) {
        this.operationType = operation;
        this.datasetName = datasetName;
        this.dataverseName = dataverseName;
        this.alterAdapterConfParams = alterAdapterConfParams;
    }

    public Identifier getDataverseName() {
        return dataverseName;
    }

    public Identifier getDatasetName() {
        return datasetName;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public void setOperation(OperationType operationType) {
        this.operationType = operationType;
    }

    @Override
    public Kind getKind() {
        return Kind.CONTROL_FEED;
    }

    public Map<String, String> getAlterAdapterConfParams() {
        return alterAdapterConfParams;
    }

    @Override
    public <R, T> R accept(IAqlExpressionVisitor<R, T> visitor, T arg) throws AsterixException {
        return visitor.visitControlFeedStatement(this, arg);
    }

    @Override
    public <T> void accept(IAqlVisitorWithVoidReturn<T> visitor, T arg) throws AsterixException {
        visitor.visit(this, arg);
    }

}
