package edu.uci.ics.asterix.dataflow.data.nontagged.printers.json;

import java.io.PrintStream;

import edu.uci.ics.asterix.dataflow.data.nontagged.serde.ADoubleSerializerDeserializer;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.data.IPrinter;

public class ADoublePrinter implements IPrinter {

    public static final ADoublePrinter INSTANCE = new ADoublePrinter();

    @Override
    public void init() {

    }

    @Override
    public void print(byte[] b, int s, int l, PrintStream ps) throws AlgebricksException {
        ps.print(ADoubleSerializerDeserializer.getDouble(b, s + 1));
    }
}