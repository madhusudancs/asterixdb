package edu.uci.ics.asterix.runtime.evaluators.functions;

import java.io.DataOutput;

import edu.uci.ics.asterix.common.functions.FunctionConstants;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptor;
import edu.uci.ics.asterix.om.functions.IFunctionDescriptorFactory;
import edu.uci.ics.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.ICopyEvaluatorFactory;
import edu.uci.ics.hyracks.data.std.primitive.UTF8StringPointable;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IDataOutputProvider;

public class ContainsDescriptor extends AbstractScalarFunctionDynamicDescriptor {
    private static final long serialVersionUID = 1L;

    private final static FunctionIdentifier FID = new FunctionIdentifier(FunctionConstants.ASTERIX_NS, "contains", 2,
            true);
    public static final IFunctionDescriptorFactory FACTORY = new IFunctionDescriptorFactory() {
        public IFunctionDescriptor createFunctionDescriptor() {
            return new ContainsDescriptor();
        }
    };

    @Override
    public ICopyEvaluatorFactory createEvaluatorFactory(final ICopyEvaluatorFactory[] args) throws AlgebricksException {

        return new ICopyEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public ICopyEvaluator createEvaluator(IDataOutputProvider output) throws AlgebricksException {

                DataOutput dout = output.getDataOutput();

                return new AbstractStringContainsEval(dout, args[0], args[1]) {

                    @Override
                    protected boolean findMatch(byte[] strBytes, byte[] patternBytes) {
                        int utflen1 = UTF8StringPointable.getUTFLength(strBytes, 1);
                        int utflen2 = UTF8StringPointable.getUTFLength(patternBytes, 1);

                        int s1Start = 3;
                        int s2Start = 3;

                        boolean matches = false;
                        int maxStart = utflen1 - utflen2;
                        int startMatch = 0;
                        while (startMatch <= maxStart) {
                            int c1 = startMatch;
                            int c2 = 0;
                            while (c1 < utflen1 && c2 < utflen2) {
                                char ch1 = UTF8StringPointable.charAt(strBytes, s1Start + c1);
                                char ch2 = UTF8StringPointable.charAt(patternBytes, s2Start + c2);

                                if (ch1 != ch2) {
                                    break;
                                }
                                c1 += UTF8StringPointable.charSize(strBytes, s1Start + c1);
                                c2 += UTF8StringPointable.charSize(patternBytes, s2Start + c2);
                            }
                            if (c2 == utflen2) {
                                matches = true;
                                break;
                            }
                            startMatch += UTF8StringPointable.charSize(strBytes, s1Start + startMatch);
                        }
                        return matches;
                    }

                };
            }
        };
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return FID;
    }

}
