package edu.uci.ics.asterix.runtime.evaluators.common;

import java.io.IOException;

import edu.uci.ics.asterix.builders.IAOrderedListBuilder;
import edu.uci.ics.asterix.builders.OrderedListBuilder;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AFloatSerializerDeserializer;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.ABoolean;
import edu.uci.ics.asterix.om.types.AOrderedListType;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.asterix.runtime.evaluators.functions.BinaryHashMap.BinaryEntry;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.data.std.primitive.IntegerPointable;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IDataOutputProvider;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class SimilarityJaccardCheckEvaluator extends SimilarityJaccardEvaluator {

    protected final ICopyEvaluator jaccThreshEval;
    protected float jaccThresh = -1f;

    protected IAOrderedListBuilder listBuilder;
    protected ArrayBackedValueStorage inputVal;
    @SuppressWarnings("unchecked")
    protected final ISerializerDeserializer<ABoolean> booleanSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ABOOLEAN);
    protected final AOrderedListType listType = new AOrderedListType(BuiltinType.ANY, "list");

    public SimilarityJaccardCheckEvaluator(ICopyEvaluatorFactory[] args, IDataOutputProvider output)
            throws AlgebricksException {
        super(args, output);
        jaccThreshEval = args[2].createEvaluator(argOut);
        listBuilder = new OrderedListBuilder();
        inputVal = new ArrayBackedValueStorage();
    }

    @Override
    protected void runArgEvals(IFrameTupleReference tuple) throws AlgebricksException {
        super.runArgEvals(tuple);
        int jaccThreshStart = argOut.getLength();
        jaccThreshEval.evaluate(tuple);
        jaccThresh = (float) AFloatSerializerDeserializer.getFloat(argOut.getByteArray(), jaccThreshStart
                + TYPE_INDICATOR_SIZE);
    }

    @Override
    protected int probeHashMap(AbstractAsterixListIterator probeIter, int buildListSize, int probeListSize) {
        // Apply length filter.
        int lengthLowerBound = (int) Math.ceil(jaccThresh * probeListSize);
        if ((lengthLowerBound > buildListSize) || (buildListSize > (int) Math.floor(1.0f / jaccThresh * probeListSize))) {
            return -1;
        }
        // Probe phase: Probe items from second list, and compute intersection size.
        int intersectionSize = 0;
        int probeListCount = 0;
        int minUnionSize = probeListSize;
        while (probeIter.hasNext()) {
            probeListCount++;
            byte[] buf = probeIter.getData();
            int off = probeIter.getPos();
            int len = getItemLen(buf, off);
            keyEntry.set(buf, off, len);            
            BinaryEntry entry = hashMap.get(keyEntry);
            if (entry != null) {
                // Increment second value.
                int firstValInt = IntegerPointable.getInteger(buf, 0);
                // Irrelevant for the intersection size.
                if (firstValInt == 0) {
                    continue;
                }
                int secondValInt = IntegerPointable.getInteger(buf, 4);
                // Subtract old min value.
                intersectionSize -= (firstValInt < secondValInt) ? firstValInt : secondValInt;
                secondValInt++;
                // Add new min value.
                intersectionSize += (firstValInt < secondValInt) ? firstValInt : secondValInt;
                IntegerPointable.setInteger(entry.buf, 0, secondValInt);            
            } else {
                // Could not find element in other set. Increase min union size by 1.
                minUnionSize++;
                // Check whether jaccThresh can still be satisfied if there was a mismatch.
                int maxIntersectionSize = intersectionSize + (probeListSize - probeListCount);
                int lowerBound = (int) Math.floor(jaccThresh * minUnionSize);
                if (maxIntersectionSize < lowerBound) {
                    // Cannot satisfy jaccThresh.
                    return -1;
                }
            }
            probeIter.next();
        }
        return intersectionSize;
    }
    
    @Override
    protected void writeResult(float jacc) throws IOException {
        listBuilder.reset(listType);
        boolean matches = (jacc < jaccThresh) ? false : true;
        inputVal.reset();
        booleanSerde.serialize(matches ? ABoolean.TRUE : ABoolean.FALSE, inputVal.getDataOutput());
        listBuilder.addItem(inputVal);

        inputVal.reset();
        aFloat.setValue((matches) ? jacc : 0.0f);
        floatSerde.serialize(aFloat, inputVal.getDataOutput());
        listBuilder.addItem(inputVal);

        listBuilder.write(out, true);
    }
}
