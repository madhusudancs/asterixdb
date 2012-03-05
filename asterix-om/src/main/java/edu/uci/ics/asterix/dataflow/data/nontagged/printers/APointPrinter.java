package edu.uci.ics.asterix.dataflow.data.nontagged.printers;

import java.io.PrintStream;

import edu.uci.ics.asterix.dataflow.data.nontagged.serde.ADoubleSerializerDeserializer;
import edu.uci.ics.hyracks.algebricks.core.algebra.data.IPrinter;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;

public class APointPrinter implements IPrinter {

    private static final long serialVersionUID = 1L;
    public static final APointPrinter INSTANCE = new APointPrinter();

    @Override
    public void init() {

    }

    @Override
    public void print(byte[] b, int s, int l, PrintStream ps) throws AlgebricksException {
        ps.print("point(\"");
        ps.print(ADoubleSerializerDeserializer.getDouble(b, s + 1));
        ps.print(",");
        ps.print(ADoubleSerializerDeserializer.getDouble(b, s + 9));
        ps.print("\")");
    }
}