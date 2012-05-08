package edu.uci.ics.asterix.runtime.evaluators.common;

import java.io.DataOutput;
import java.io.IOException;

import edu.uci.ics.asterix.builders.IARecordBuilder;
import edu.uci.ics.asterix.builders.RecordBuilder;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluatorFactory;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IDataOutputProvider;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class ClosedRecordConstructorEvalFactory implements IEvaluatorFactory {

    private static final long serialVersionUID = 1L;

    private IEvaluatorFactory[] args;
    private ARecordType recType;

    public ClosedRecordConstructorEvalFactory(IEvaluatorFactory[] args, ARecordType recType) {
        this.args = args;
        this.recType = recType;
    }

    @Override
    public IEvaluator createEvaluator(final IDataOutputProvider output) throws AlgebricksException {
        int n = args.length / 2;
        IEvaluator[] evalFields = new IEvaluator[n];
        ArrayBackedValueStorage fieldValueBuffer = new ArrayBackedValueStorage();
        for (int i = 0; i < n; i++) {
            evalFields[i] = args[2 * i + 1].createEvaluator(fieldValueBuffer);
        }
        DataOutput out = output.getDataOutput();
        return new ClosedRecordConstructorEval(recType, evalFields, fieldValueBuffer, out);
    }

    public static class ClosedRecordConstructorEval implements IEvaluator {

        private IEvaluator[] evalFields;
        private DataOutput out;
        private IARecordBuilder recBuilder = new RecordBuilder();
        private ARecordType recType;
        private ArrayBackedValueStorage fieldValueBuffer = new ArrayBackedValueStorage();
        private final static byte SER_NULL_TYPE_TAG = ATypeTag.NULL.serialize();
        private boolean first = true;

        public ClosedRecordConstructorEval(ARecordType recType, IEvaluator[] evalFields,
                ArrayBackedValueStorage fieldValueBuffer, DataOutput out) {
            this.evalFields = evalFields;
            this.fieldValueBuffer = fieldValueBuffer;
            this.out = out;
            this.recType = recType;
        }

        @Override
        public void evaluate(IFrameTupleReference tuple) throws AlgebricksException {
            try {
                if (first) {
                    first = false;
                    recBuilder.reset(this.recType);
                }
                recBuilder.init();
                for (int i = 0; i < evalFields.length; i++) {
                    fieldValueBuffer.reset();
                    evalFields[i].evaluate(tuple);
                    if (fieldValueBuffer.getBytes()[0] != SER_NULL_TYPE_TAG) {
                        recBuilder.addField(i, fieldValueBuffer);
                    }
                }
                recBuilder.write(out, true);
            } catch (IOException ioe) {
                throw new AlgebricksException(ioe);
            }
        }
    }

}
