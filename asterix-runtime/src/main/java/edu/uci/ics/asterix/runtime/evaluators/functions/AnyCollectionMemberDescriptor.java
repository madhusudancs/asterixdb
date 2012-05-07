package edu.uci.ics.asterix.runtime.evaluators.functions;

import java.io.DataOutput;
import java.io.IOException;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.common.functions.FunctionConstants;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AOrderedListSerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AUnorderedListSerializerDeserializer;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.ANull;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.om.util.NonTaggedFormatUtil;
import edu.uci.ics.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.IEvaluatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IDataOutputProvider;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class AnyCollectionMemberDescriptor extends AbstractScalarFunctionDynamicDescriptor {

    private static final long serialVersionUID = 1L;
    private final static FunctionIdentifier FID = new FunctionIdentifier(FunctionConstants.ASTERIX_NS,
            "any-collection-member", 1, true);

    @Override
    public IEvaluatorFactory createEvaluatorFactory(final IEvaluatorFactory[] args) {
        return new AnyCollectionMemberEvalFactory(args[0]);
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return FID;
    }

    private static class AnyCollectionMemberEvalFactory implements IEvaluatorFactory {

        private static final long serialVersionUID = 1L;

        private IEvaluatorFactory listEvalFactory;
        private final static byte SER_ORDEREDLIST_TYPE_TAG = ATypeTag.ORDEREDLIST.serialize();
        private final static byte SER_UNORDEREDLIST_TYPE_TAG = ATypeTag.UNORDEREDLIST.serialize();
        private final static byte SER_NULL_TYPE_TAG = ATypeTag.NULL.serialize();
        private byte serItemTypeTag;
        private ATypeTag itemTag;
        private boolean selfDescList = false;

        public AnyCollectionMemberEvalFactory(IEvaluatorFactory arg) {
            this.listEvalFactory = arg;
        }

        @Override
        public IEvaluator createEvaluator(final IDataOutputProvider output) throws AlgebricksException {
            return new IEvaluator() {

                private DataOutput out = output.getDataOutput();
                private ArrayBackedValueStorage outInputList = new ArrayBackedValueStorage();
                private IEvaluator evalList = listEvalFactory.createEvaluator(outInputList);
                @SuppressWarnings("unchecked")
                private ISerializerDeserializer<ANull> nullSerde = AqlSerializerDeserializerProvider.INSTANCE
                        .getSerializerDeserializer(BuiltinType.ANULL);
                private int itemOffset;
                private int itemLength;

                @Override
                public void evaluate(IFrameTupleReference tuple) throws AlgebricksException {

                    try {
                        outInputList.reset();
                        evalList.evaluate(tuple);
                        byte[] serList = outInputList.getBytes();

                        if (serList[0] == SER_NULL_TYPE_TAG) {
                            nullSerde.serialize(ANull.NULL, out);
                            return;
                        }

                        if (serList[0] != SER_ORDEREDLIST_TYPE_TAG && serList[0] != SER_UNORDEREDLIST_TYPE_TAG) {
                            throw new AlgebricksException("List's get-any-item is not defined for values of type"
                                    + EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(serList[0]));
                        }

                        if (serList[0] == SER_ORDEREDLIST_TYPE_TAG) {
                            if (AOrderedListSerializerDeserializer.getNumberOfItems(serList) == 0) {
                                out.writeByte(SER_NULL_TYPE_TAG);
                                return;
                            }
                            itemOffset = AOrderedListSerializerDeserializer.getItemOffset(serList, 0);
                        } else {
                            if (AUnorderedListSerializerDeserializer.getNumberOfItems(serList) == 0) {
                                out.writeByte(SER_NULL_TYPE_TAG);
                                return;
                            }
                            itemOffset = AUnorderedListSerializerDeserializer.getItemOffset(serList, 0);
                        }

                        itemTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(serList[1]);
                        if (itemTag == ATypeTag.ANY)
                            selfDescList = true;
                        else
                            serItemTypeTag = serList[1];

                        if (selfDescList) {
                            itemTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(serList[itemOffset]);
                            itemLength = NonTaggedFormatUtil.getFieldValueLength(serList, itemOffset, itemTag, true) + 1;
                            out.write(serList, itemOffset, itemLength);
                        } else {
                            itemLength = NonTaggedFormatUtil.getFieldValueLength(serList, itemOffset, itemTag, false);
                            out.writeByte(serItemTypeTag);
                            out.write(serList, itemOffset, itemLength);
                        }
                    } catch (IOException e) {
                        throw new AlgebricksException(e);
                    } catch (AsterixException e) {
                        throw new AlgebricksException(e);
                    }
                }
            };
        }

    }

}
