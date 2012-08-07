package edu.uci.ics.asterix.formats.nontagged;

import java.io.Serializable;

import edu.uci.ics.asterix.dataflow.data.nontagged.comparators.ADateTimeAscBinaryComparatorFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.comparators.AObjectAscBinaryComparatorFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.comparators.AObjectDescBinaryComparatorFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.comparators.BooleanBinaryComparatorFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.comparators.RectangleBinaryComparatorFactory;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.common.exceptions.NotImplementedException;
import edu.uci.ics.hyracks.algebricks.data.IBinaryComparatorFactoryProvider;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparator;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.data.std.accessors.PointableBinaryComparatorFactory;
import edu.uci.ics.hyracks.data.std.primitive.BytePointable;
import edu.uci.ics.hyracks.data.std.primitive.DoublePointable;
import edu.uci.ics.hyracks.data.std.primitive.FloatPointable;
import edu.uci.ics.hyracks.data.std.primitive.IntegerPointable;
import edu.uci.ics.hyracks.data.std.primitive.LongPointable;
import edu.uci.ics.hyracks.data.std.primitive.ShortPointable;
import edu.uci.ics.hyracks.data.std.primitive.UTF8StringPointable;

public class AqlBinaryComparatorFactoryProvider implements IBinaryComparatorFactoryProvider, Serializable {

    private static final long serialVersionUID = 1L;
    public static final AqlBinaryComparatorFactoryProvider INSTANCE = new AqlBinaryComparatorFactoryProvider();
    public static final PointableBinaryComparatorFactory BYTE_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            BytePointable.FACTORY);
    public static final PointableBinaryComparatorFactory SHORT_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            ShortPointable.FACTORY);
    public static final PointableBinaryComparatorFactory INTEGER_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            IntegerPointable.FACTORY);
    public static final PointableBinaryComparatorFactory LONG_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            LongPointable.FACTORY);
    public static final PointableBinaryComparatorFactory FLOAT_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            FloatPointable.FACTORY);
    public static final PointableBinaryComparatorFactory DOUBLE_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            DoublePointable.FACTORY);
    public static final PointableBinaryComparatorFactory UTF8STRING_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            UTF8StringPointable.FACTORY);
    // Equivalent to UTF8STRING_POINTABLE_INSTANCE but all characters are considered lower case to implement case-insensitive comparisons.    
    public static final PointableBinaryComparatorFactory UTF8STRING_LOWERCASE_POINTABLE_INSTANCE = new PointableBinaryComparatorFactory(
            UTF8StringLowercasePointable.FACTORY);

    private AqlBinaryComparatorFactoryProvider() {
    }

    // This method add the option of ignoring the case in string comparisons.
    // TODO: We should incorporate this option more nicely, but I'd have to change algebricks.
    public IBinaryComparatorFactory getBinaryComparatorFactory(Object type, boolean ascending, boolean ignoreCase) {
        if (type == null) {
            return anyBinaryComparatorFactory(ascending);
        }
        IAType aqlType = (IAType) type;
        if (aqlType.getTypeTag() == ATypeTag.STRING && ignoreCase) {
            return addOffset(UTF8STRING_LOWERCASE_POINTABLE_INSTANCE, ascending);
        }
        return getBinaryComparatorFactory(type, ascending);
    }

    @Override
    public IBinaryComparatorFactory getBinaryComparatorFactory(Object type, boolean ascending) {
        if (type == null) {
            return anyBinaryComparatorFactory(ascending);
        }        
        IAType aqlType = (IAType) type;
        return getBinaryComparatorFactory(aqlType.getTypeTag(), ascending);
    }
    
    public IBinaryComparatorFactory getBinaryComparatorFactory(ATypeTag type, boolean ascending) {
        switch (type) {
            case ANY:
            case UNION: { // we could do smth better for nullable fields
                return anyBinaryComparatorFactory(ascending);
            }
            case NULL: {
                return new IBinaryComparatorFactory() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public IBinaryComparator createBinaryComparator() {
                        return new IBinaryComparator() {

                            @Override
                            public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
                                return 0;
                            }
                        };
                    }
                };
            }
            case BOOLEAN: {
                return addOffset(BooleanBinaryComparatorFactory.INSTANCE, ascending);
            }
            case INT8: {
                return addOffset(BYTE_POINTABLE_INSTANCE, ascending);
            }
            case INT16: {
                return addOffset(SHORT_POINTABLE_INSTANCE, ascending);
            }
            case INT32: {
                return addOffset(INTEGER_POINTABLE_INSTANCE, ascending);
            }
            case INT64: {
                return addOffset(LONG_POINTABLE_INSTANCE, ascending);
            }
            case FLOAT: {
                return addOffset(FLOAT_POINTABLE_INSTANCE, ascending);
            }
            case DOUBLE: {
                return addOffset(DOUBLE_POINTABLE_INSTANCE, ascending);
            }
            case STRING: {
                return addOffset(UTF8STRING_POINTABLE_INSTANCE, ascending);
            }
            case RECTANGLE: {
                return addOffset(RectangleBinaryComparatorFactory.INSTANCE, ascending);
            }
            case DATE:
            case TIME:
            case DATETIME: {
                return addOffset(ADateTimeAscBinaryComparatorFactory.INSTANCE, ascending);
            }
            default: {
                throw new NotImplementedException("No binary comparator factory implemented for type "
                        + type + " .");
            }
        }
    }

    private IBinaryComparatorFactory addOffset(final IBinaryComparatorFactory inst, final boolean ascending) {
        return new IBinaryComparatorFactory() {

            private static final long serialVersionUID = 1L;

            @Override
            public IBinaryComparator createBinaryComparator() {
                final IBinaryComparator bc = inst.createBinaryComparator();
                if (ascending) {
                    return new IBinaryComparator() {

                        @Override
                        public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
                            return bc.compare(b1, s1 + 1, l1 - 1, b2, s2 + 1, l2 - 1);
                        }
                    };
                } else {
                    return new IBinaryComparator() {
                        @Override
                        public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
                            return -bc.compare(b1, s1 + 1, l1 - 1, b2, s2 + 1, l2 - 1);
                        }
                    };
                }
            }
        };
    }

    private IBinaryComparatorFactory anyBinaryComparatorFactory(boolean ascending) {
        if (ascending) {
            return AObjectAscBinaryComparatorFactory.INSTANCE;
        } else {
            return AObjectDescBinaryComparatorFactory.INSTANCE;
        }
    }
}
