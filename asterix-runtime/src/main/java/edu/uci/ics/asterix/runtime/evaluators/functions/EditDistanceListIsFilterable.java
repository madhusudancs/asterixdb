package edu.uci.ics.asterix.runtime.evaluators.functions;

import java.io.IOException;

import edu.uci.ics.asterix.common.functions.FunctionConstants;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AOrderedListSerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AUnorderedListSerializerDeserializer;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.ABoolean;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptor;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptorFactory;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.data.std.api.IDataOutputProvider;
import edu.uci.ics.hyracks.data.std.util.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;

/**
 * Checks whether a list with an edit distance threshold can be filtered with a lower bounding on the number of common list elements.
 * This function returns 'true' if the lower bound on the number of common elements is positive, 'false' otherwise.
 * For example, this function is used during an indexed nested-loop join based on edit distance. We partition the tuples of the probing
 * dataset into those that are filterable and those that are not. Those that are filterable are forwarded to the index. The others are
 * are fed into a (non indexed) nested-loop join.
 */
public class EditDistanceListIsFilterable extends AbstractScalarFunctionDynamicDescriptor {

    private static final long serialVersionUID = 1L;
    private final static FunctionIdentifier FID = new FunctionIdentifier(FunctionConstants.ASTERIX_NS,
            "edit-distance-list-is-filterable", 2);
    public static final IFunctionDescriptorFactory FACTORY = new IFunctionDescriptorFactory() {
        public IFunctionDescriptor createFunctionDescriptor() {
            return new EditDistanceListIsFilterable();
        }
    };

    @Override
    public ICopyEvaluatorFactory createEvaluatorFactory(final ICopyEvaluatorFactory[] args) throws AlgebricksException {
        return new ICopyEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public ICopyEvaluator createEvaluator(IDataOutputProvider output) throws AlgebricksException {
                return new EditDistanceListIsFilterableEvaluator(args, output);
            }
        };
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return FID;
    }

    private static class EditDistanceListIsFilterableEvaluator implements ICopyEvaluator {

        protected final ArrayBackedValueStorage argBuf = new ArrayBackedValueStorage();
        protected final IDataOutputProvider output;

        protected final ICopyEvaluator listEval;
        protected final ICopyEvaluator edThreshEval;

        @SuppressWarnings("unchecked")
        private final ISerializerDeserializer<ABoolean> booleanSerde = AqlSerializerDeserializerProvider.INSTANCE
                .getSerializerDeserializer(BuiltinType.ABOOLEAN);

        public EditDistanceListIsFilterableEvaluator(ICopyEvaluatorFactory[] args, IDataOutputProvider output)
                throws AlgebricksException {
            this.output = output;
            listEval = args[0].createEvaluator(argBuf);
            edThreshEval = args[1].createEvaluator(argBuf);
        }

        @Override
        public void evaluate(IFrameTupleReference tuple) throws AlgebricksException {
            ATypeTag typeTag = null;

            // Check type and compute string length.
            argBuf.reset();
            listEval.evaluate(tuple);
            typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(argBuf.getByteArray()[0]);
            int listLen = 0;
            switch (typeTag) {
                case UNORDEREDLIST: {
                    listLen = AUnorderedListSerializerDeserializer.getNumberOfItems(argBuf.getByteArray(), 0);
                    break;
                }
                case ORDEREDLIST: {
                    listLen = AOrderedListSerializerDeserializer.getNumberOfItems(argBuf.getByteArray(), 0);
                    break;
                }
                default: {
                    throw new AlgebricksException(
                            "Expected type 'ORDEREDLIST' or 'UNORDEREDLIST' as first argument. Encountered '"
                                    + typeTag.toString() + "'.");
                }
            }

            // Check type and extract edit-distance threshold.
            argBuf.reset();
            edThreshEval.evaluate(tuple);
            typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(argBuf.getByteArray()[0]);
            if (!typeTag.equals(ATypeTag.INT32)) {
                throw new AlgebricksException("Expected type 'INT32' as second argument. Encountered '"
                        + typeTag.toString() + "'.");
            }
            int edThresh = IntegerSerializerDeserializer.getInt(argBuf.getByteArray(), 1);

            // Compute result.
            int lowerBound = listLen - edThresh;
            try {
                if (lowerBound <= 0) {
                    booleanSerde.serialize(ABoolean.FALSE, output.getDataOutput());
                } else {
                    booleanSerde.serialize(ABoolean.TRUE, output.getDataOutput());
                }
            } catch (IOException e) {
                throw new AlgebricksException(e);
            }
        }
    }
}
