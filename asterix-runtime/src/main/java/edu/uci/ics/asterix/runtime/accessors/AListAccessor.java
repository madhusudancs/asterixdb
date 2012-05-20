package edu.uci.ics.asterix.runtime.accessors;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt32SerializerDeserializer;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.AbstractCollectionType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.om.util.NonTaggedFormatUtil;
import edu.uci.ics.asterix.runtime.accessors.base.IBinaryAccessor;
import edu.uci.ics.asterix.runtime.accessors.visitor.IBinaryAccessorVisitor;
import edu.uci.ics.asterix.runtime.util.ResettableByteArrayOutputStream;
import edu.uci.ics.asterix.runtime.util.container.IElementFactory;

public class AListAccessor extends AbstractBinaryAccessor {

    public static IElementFactory<IBinaryAccessor, IAType> FACTORY = new IElementFactory<IBinaryAccessor, IAType>() {
        public IBinaryAccessor createElement(IAType type) {
            return new AListAccessor((AbstractCollectionType) type);
        }
    };

    private AbstractCollectionType inputType;
    private IAType itemType;
    private ATypeTag itemTag;
    private boolean typedItemList = false;

    private List<IBinaryAccessor> items = new ArrayList<IBinaryAccessor>();
    private List<IBinaryAccessor> itemTags = new ArrayList<IBinaryAccessor>();
    private AccessorAllocator allocator = new AccessorAllocator();

    private byte[] typeBuffer = new byte[32768];
    private ResettableByteArrayOutputStream typeBos = new ResettableByteArrayOutputStream();
    private DataOutputStream typeDos = new DataOutputStream(typeBos);

    private byte[] dataBuffer = new byte[32768];
    private ResettableByteArrayOutputStream dataBos = new ResettableByteArrayOutputStream();
    private DataOutputStream dataDos = new DataOutputStream(dataBos);

    private AListAccessor(AbstractCollectionType inputType) {
        this.inputType = inputType;
        if (inputType != null && inputType.getItemType() != null) {
            itemType = inputType.getItemType();
            if (itemType.getTypeTag() == ATypeTag.ANY) {
                typedItemList = false;
            } else {
                typedItemList = true;
                itemTag = inputType.getItemType().getTypeTag();
            }
        } else {
            this.typedItemList = false;
        }
    }

    private void reset() {
        allocator.reset();
        items.clear();
        
        typeBos.setByteArray(typeBuffer, 0);
        dataBos.setByteArray(dataBuffer, 0);
    }

    public List<IBinaryAccessor> getItems() {
        return items;
    }

    public List<IBinaryAccessor> getItemTags() {
        return itemTags;
    }

    @Override
    public void reset(byte[] b, int s, int len) {
        reset();

        int numberOfitems = AInt32SerializerDeserializer.getInt(b, s + 6);
        int itemOffset;
        if (typedItemList) {
            switch (itemTag) {
                case STRING:
                case RECORD:
                case ORDEREDLIST:
                case UNORDEREDLIST:
                case ANY:
                    itemOffset = s + 10 + (numberOfitems * 4);
                    break;
                default:
                    itemOffset = s + 10;
            }
        } else
            itemOffset = s + 10 + (numberOfitems * 4);
        int itemLength = 0;
        try {
            if (typedItemList) {
                for (int i = 0; i < numberOfitems; i++) {
                    itemLength = NonTaggedFormatUtil.getFieldValueLength(b, itemOffset, itemTag, false);
                    IBinaryAccessor field = allocator.allocateFieldValue(itemType);
                    field.reset(b, itemOffset, itemLength);
                    itemOffset += itemLength;
                }
            } else {
                for (int i = 0; i < numberOfitems; i++) {
                    itemTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(b[itemOffset]);
                    itemLength = NonTaggedFormatUtil.getFieldValueLength(b, itemOffset, itemTag, true) + 1;
                    IBinaryAccessor field = allocator.allocateFieldValue(itemType);
                    field.reset(b, itemOffset, itemLength);
                    itemOffset += itemLength;
                }
            }
        } catch (AsterixException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public <R, T> R accept(IBinaryAccessorVisitor<R, T> vistor, T tag) throws AsterixException {
        return vistor.visit(this, tag);
    }

}
