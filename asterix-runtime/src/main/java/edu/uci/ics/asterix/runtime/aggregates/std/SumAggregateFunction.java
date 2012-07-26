package edu.uci.ics.asterix.runtime.aggregates.std;

import java.io.DataOutput;
import java.io.IOException;

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
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.exceptions.NotImplementedException;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyAggregateFunction;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.data.std.api.IDataOutputProvider;
import edu.uci.ics.hyracks.data.std.util.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class SumAggregateFunction implements ICopyAggregateFunction {
    private DataOutput out;
    private ArrayBackedValueStorage inputVal = new ArrayBackedValueStorage();
    private ICopyEvaluator eval;
    private boolean metInt8s, metInt16s, metInt32s, metInt64s, metFloats, metDoubles, metNull;
    private double sum;
    private AMutableDouble aDouble = new AMutableDouble(0);
    private AMutableFloat aFloat = new AMutableFloat(0);
    private AMutableInt64 aInt64 = new AMutableInt64(0);
    private AMutableInt32 aInt32 = new AMutableInt32(0);
    private AMutableInt16 aInt16 = new AMutableInt16((short) 0);
    private AMutableInt8 aInt8 = new AMutableInt8((byte) 0);
    @SuppressWarnings("rawtypes")
    private ISerializerDeserializer serde;

    private final boolean isLocalAgg;

    public SumAggregateFunction(ICopyEvaluatorFactory[] args, IDataOutputProvider provider, boolean isLocalAgg)
            throws AlgebricksException {
        out = provider.getDataOutput();
        eval = args[0].createEvaluator(inputVal);
        this.isLocalAgg = isLocalAgg;
    }

    @Override
    public void init() {
        metInt8s = false;
        metInt16s = false;
        metInt32s = false;
        metInt64s = false;
        metFloats = false;
        metDoubles = false;
        metNull = false;
        sum = 0.0;
    }

    @Override
    public void step(IFrameTupleReference tuple) throws AlgebricksException {
        inputVal.reset();
        eval.evaluate(tuple);
        if (inputVal.getLength() > 0) {
            ATypeTag typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(inputVal.getByteArray()[0]);
            switch (typeTag) {
                case INT8: {
                    metInt8s = true;
                    byte val = AInt8SerializerDeserializer.getByte(inputVal.getByteArray(), 1);
                    sum += val;
                    break;
                }
                case INT16: {
                    metInt16s = true;
                    short val = AInt16SerializerDeserializer.getShort(inputVal.getByteArray(), 1);
                    sum += val;
                    break;
                }
                case INT32: {
                    metInt32s = true;
                    int val = AInt32SerializerDeserializer.getInt(inputVal.getByteArray(), 1);
                    sum += val;
                    break;
                }
                case INT64: {
                    metInt64s = true;
                    long val = AInt64SerializerDeserializer.getLong(inputVal.getByteArray(), 1);
                    sum += val;
                    break;
                }
                case FLOAT: {
                    metFloats = true;
                    float val = AFloatSerializerDeserializer.getFloat(inputVal.getByteArray(), 1);
                    sum += val;
                    break;
                }
                case DOUBLE: {
                    metDoubles = true;
                    double val = ADoubleSerializerDeserializer.getDouble(inputVal.getByteArray(), 1);
                    sum += val;
                    break;
                }
                case NULL: {
                    metNull = true;
                    break;
                }
                case SYSTEM_NULL: {
                    // For global aggregates simply ignore system null here,
                    // but if all input value are system null, then we should return
                    // null in finish().
                    if (isLocalAgg) {
                        throw new AlgebricksException("Type SYSTEM_NULL encountered in local aggregate.");
                    }
                    break;
                }
                default: {
                    throw new NotImplementedException("Cannot compute SUM for values of type " + typeTag);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void finish() throws AlgebricksException {
        try {
            if (metNull) {
                serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ANULL);
                serde.serialize(ANull.NULL, out);
            } else if (metDoubles) {
                serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ADOUBLE);
                aDouble.setValue(sum);
                serde.serialize(aDouble, out);
            } else if (metFloats) {
                serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AFLOAT);
                aFloat.setValue((float) sum);
                serde.serialize(aFloat, out);
            } else if (metInt64s) {
                serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AINT64);
                aInt64.setValue((long) sum);
                serde.serialize(aInt64, out);
            } else if (metInt32s) {
                serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AINT32);
                aInt32.setValue((int) sum);
                serde.serialize(aInt32, out);
            } else if (metInt16s) {
                serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AINT16);
                aInt16.setValue((short) sum);
                serde.serialize(aInt16, out);
            } else if (metInt8s) {
                serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AINT8);
                aInt8.setValue((byte) sum);
                serde.serialize(aInt8, out);
            } else {
                // Empty stream. For local agg return system null. For global agg return null.
                if (isLocalAgg) {
                    out.writeByte(ATypeTag.SYSTEM_NULL.serialize());
                } else {
                    serde = AqlSerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ANULL);
                    serde.serialize(ANull.NULL, out);
                }
            }
        } catch (IOException e) {
            throw new AlgebricksException(e);
        }
    }

    @Override
    public void finishPartial() throws AlgebricksException {
        finish();
    }
}
