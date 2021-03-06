package edu.uci.ics.asterix.runtime.evaluators.functions;

import java.io.DataOutput;
import java.io.IOException;

import edu.uci.ics.asterix.om.functions.AsterixBuiltinFunctions;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptor;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptorFactory;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluatorFactory;
import edu.uci.ics.hyracks.data.std.api.IDataOutputProvider;
import edu.uci.ics.hyracks.data.std.primitive.UTF8StringPointable;
import edu.uci.ics.hyracks.data.std.util.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;

public class Substring2Descriptor extends AbstractScalarFunctionDynamicDescriptor {

    private static final long serialVersionUID = 1L;

    // allowed input types
    private static final byte SER_INT32_TYPE_TAG = ATypeTag.INT32.serialize();
    private static final byte SER_STRING_TYPE_TAG = ATypeTag.STRING.serialize();

    public static final IFunctionDescriptorFactory FACTORY = new IFunctionDescriptorFactory() {
        public IFunctionDescriptor createFunctionDescriptor() {
            return new Substring2Descriptor();
        }
    };

    @Override
    public ICopyEvaluatorFactory createEvaluatorFactory(final ICopyEvaluatorFactory[] args) throws AlgebricksException {
        return new ICopyEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public ICopyEvaluator createEvaluator(final IDataOutputProvider output) throws AlgebricksException {
                return new ICopyEvaluator() {

                    private DataOutput out = output.getDataOutput();
                    private ArrayBackedValueStorage argOut = new ArrayBackedValueStorage();
                    private ICopyEvaluator evalString = args[0].createEvaluator(argOut);
                    private ICopyEvaluator evalStart = args[1].createEvaluator(argOut);
                    private final byte stt = ATypeTag.STRING.serialize();

                    @Override
                    public void evaluate(IFrameTupleReference tuple) throws AlgebricksException {
                        argOut.reset();
                        evalStart.evaluate(tuple);
                        if (argOut.getByteArray()[0] != SER_INT32_TYPE_TAG) {
                            throw new AlgebricksException(AsterixBuiltinFunctions.SUBSTRING2.getName()
                                    + ": expects type INT32 for the second argument but got "
                                    + EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(argOut.getByteArray()[0]));
                        }
                        int start = IntegerSerializerDeserializer.getInt(argOut.getByteArray(), 1) - 1;
                        argOut.reset();
                        evalString.evaluate(tuple);

                        byte[] bytes = argOut.getByteArray();
                        if (bytes[0] != SER_STRING_TYPE_TAG) {
                            throw new AlgebricksException(AsterixBuiltinFunctions.SUBSTRING2.getName()
                                    + ": expects type STRING for the first argument but got "
                                    + EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(argOut.getByteArray()[0]));
                        }
                        int utflen = UTF8StringPointable.getUTFLength(bytes, 1);
                        int sStart = 3;
                        int c = 0;
                        int idxPos1 = 0;
                        // skip to start
                        while (idxPos1 < start && c < utflen) {
                            c += UTF8StringPointable.charSize(bytes, sStart + c);
                            ++idxPos1;
                        }
                        int startSubstr = c;

                        while (c < utflen) {
                            c += UTF8StringPointable.charSize(bytes, sStart + c);
                        }

                        int substrByteLen = c - startSubstr;
                        try {
                            out.writeByte(stt);
                            out.writeByte((byte) ((substrByteLen >>> 8) & 0xFF));
                            out.writeByte((byte) ((substrByteLen >>> 0) & 0xFF));
                            out.write(bytes, sStart + startSubstr, substrByteLen);

                        } catch (IOException e) {
                            throw new AlgebricksException(e);
                        }
                    }
                };
            }
        };
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return AsterixBuiltinFunctions.SUBSTRING2;
    }

}
