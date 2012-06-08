package edu.uci.ics.asterix.runtime.evaluators.functions;

import java.io.DataOutput;

import edu.uci.ics.asterix.common.functions.FunctionConstants;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.ADoubleSerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AFloatSerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt16SerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt32SerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt64SerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt8SerializerDeserializer;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.AMutableDouble;
import edu.uci.ics.asterix.om.base.AMutableFloat;
import edu.uci.ics.asterix.om.base.AMutableInt16;
import edu.uci.ics.asterix.om.base.AMutableInt32;
import edu.uci.ics.asterix.om.base.AMutableInt64;
import edu.uci.ics.asterix.om.base.AMutableInt8;
import edu.uci.ics.asterix.om.base.ANull;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptor;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptorFactory;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.exceptions.NotImplementedException;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IDataOutputProvider;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class NumericSubtractDescriptor extends AbstractScalarFunctionDynamicDescriptor {

    private static final long serialVersionUID = 1L;
    public final static FunctionIdentifier FID = new FunctionIdentifier(FunctionConstants.ASTERIX_NS,
            "numeric-subtract", 2, true);
    public static final IFunctionDescriptorFactory FACTORY = new IFunctionDescriptorFactory() {
        public IFunctionDescriptor createFunctionDescriptor() {
            return new NumericSubtractDescriptor();
        }
    };

    @Override
    public FunctionIdentifier getIdentifier() {
        return FID;
    }

    @Override
    public IEvaluatorFactory createEvaluatorFactory(final IEvaluatorFactory[] args) throws AlgebricksException {
        return new IEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public IEvaluator createEvaluator(final IDataOutputProvider output) throws AlgebricksException {

                return new IEvaluator() {
                    private DataOutput out = output.getDataOutput();
                    // one temp. buffer re-used by both children
                    private ArrayBackedValueStorage argOut = new ArrayBackedValueStorage();
                    private IEvaluator evalLeft = args[0].createEvaluator(argOut);
                    private IEvaluator evalRight = args[1].createEvaluator(argOut);
                    private double[] operands = new double[args.length];
                    private boolean metInt8 = false, metInt16 = false, metInt32 = false, metInt64 = false,
                            metFloat = false, metDouble = false;
                    private ATypeTag typeTag;
                    private AMutableDouble aDouble = new AMutableDouble(0);
                    private AMutableFloat aFloat = new AMutableFloat(0);
                    private AMutableInt64 aInt64 = new AMutableInt64(0);
                    private AMutableInt32 aInt32 = new AMutableInt32(0);
                    private AMutableInt16 aInt16 = new AMutableInt16((short) 0);
                    private AMutableInt8 aInt8 = new AMutableInt8((byte) 0);
                    @SuppressWarnings("rawtypes")
                    private ISerializerDeserializer serde;

                    @SuppressWarnings("unchecked")
                    @Override
                    public void evaluate(IFrameTupleReference tuple) throws AlgebricksException {

                        try {
                            for (int i = 0; i < args.length; i++) {
                                argOut.reset();
                                if (i == 0)
                                    evalLeft.evaluate(tuple);
                                else
                                    evalRight.evaluate(tuple);
                                typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(argOut.getByteArray()[0]);
                                switch (typeTag) {
                                    case INT8: {
                                        metInt8 = true;
                                        operands[i] = AInt8SerializerDeserializer.getByte(argOut.getByteArray(), 1);
                                        break;
                                    }
                                    case INT16: {
                                        metInt16 = true;
                                        operands[i] = AInt16SerializerDeserializer.getShort(argOut.getByteArray(), 1);
                                        break;
                                    }
                                    case INT32: {
                                        metInt32 = true;
                                        operands[i] = AInt32SerializerDeserializer.getInt(argOut.getByteArray(), 1);
                                        break;
                                    }
                                    case INT64: {
                                        metInt64 = true;
                                        operands[i] = AInt64SerializerDeserializer.getLong(argOut.getByteArray(), 1);
                                        break;
                                    }
                                    case FLOAT: {
                                        metFloat = true;
                                        operands[i] = AFloatSerializerDeserializer.getFloat(argOut.getByteArray(), 1);
                                        break;
                                    }
                                    case DOUBLE: {
                                        metDouble = true;
                                        operands[i] = ADoubleSerializerDeserializer.getDouble(argOut.getByteArray(), 1);
                                        break;
                                    }
                                    case NULL: {
                                        serde = AqlSerializerDeserializerProvider.INSTANCE
                                                .getSerializerDeserializer(BuiltinType.ANULL);
                                        serde.serialize(ANull.NULL, out);
                                        return;
                                    }
                                    default: {
                                        throw new NotImplementedException(i == 0 ? "Left"
                                                : "Right"
                                                        + " Operand of Substraction can not be "
                                                        + EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(argOut
                                                                .getByteArray()[0]));
                                    }
                                }
                            }

                            if (metDouble) {
                                serde = AqlSerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.ADOUBLE);
                                aDouble.setValue(operands[0] - operands[1]);
                                serde.serialize(aDouble, out);
                            } else if (metFloat) {
                                serde = AqlSerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AFLOAT);
                                aFloat.setValue((float) (operands[0] - operands[1]));
                                serde.serialize(aFloat, out);
                            } else if (metInt64) {
                                serde = AqlSerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT64);
                                aInt64.setValue((long) (operands[0] - operands[1]));
                                serde.serialize(aInt64, out);
                            } else if (metInt32) {
                                serde = AqlSerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT32);
                                aInt32.setValue((int) (operands[0] - operands[1]));
                                serde.serialize(aInt32, out);
                            } else if (metInt16) {
                                serde = AqlSerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT16);
                                aInt16.setValue((short) (operands[0] - operands[1]));
                                serde.serialize(aInt16, out);
                            } else if (metInt8) {
                                serde = AqlSerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT8);
                                aInt8.setValue((byte) (operands[0] - operands[1]));
                                serde.serialize(aInt8, out);
                            }

                        } catch (HyracksDataException hde) {
                            throw new AlgebricksException(hde);
                        }
                    }
                };
            }
        };
    }

}
