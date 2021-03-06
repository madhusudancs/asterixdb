package edu.uci.ics.asterix.dataflow.data.nontagged.printers.json;

import java.io.PrintStream;

import edu.uci.ics.asterix.dataflow.data.nontagged.serde.ADoubleSerializerDeserializer;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt16SerializerDeserializer;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.data.IPrinter;

public class APolygonPrinter implements IPrinter {

    public static final APolygonPrinter INSTANCE = new APolygonPrinter();

    @Override
    public void init() {

    }

    @Override
    public void print(byte[] b, int s, int l, PrintStream ps) throws AlgebricksException {
        short numberOfPoints = AInt16SerializerDeserializer.getShort(b, s + 1);
        s += 3;

        ps.print("{ polygon: [");

        for (int i = 0; i < numberOfPoints; i++) {
            if (i > 0)
                ps.print(",");

            ps.print("{ point: [");
            ps.print(ADoubleSerializerDeserializer.getDouble(b, s));
            ps.print(",");
            ps.print(ADoubleSerializerDeserializer.getDouble(b, s + 8));
            ps.print("]}");

            s += 16;
        }

        ps.print("]}");

    }
}