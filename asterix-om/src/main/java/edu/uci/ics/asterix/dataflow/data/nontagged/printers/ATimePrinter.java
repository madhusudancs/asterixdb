package edu.uci.ics.asterix.dataflow.data.nontagged.printers;

import java.io.PrintStream;

import edu.uci.ics.asterix.dataflow.data.nontagged.serde.AInt32SerializerDeserializer;
import edu.uci.ics.asterix.om.base.temporal.GregorianCalendarSystem;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.data.IPrinter;

public class ATimePrinter implements IPrinter {

    private static final long serialVersionUID = 1L;
    public static final ATimePrinter INSTANCE = new ATimePrinter();

    @Override
    public void init() {

    }

    @Override
    public void print(byte[] b, int s, int l, PrintStream ps) throws AlgebricksException {
        int time = AInt32SerializerDeserializer.getInt(b, s + 1);
        GregorianCalendarSystem calendar = GregorianCalendarSystem.getInstance();
        ps.print("time(\"");

        ps.append(String.format("%02d", calendar.getHourOfDay(time))).append(":")
                .append(String.format("%02d", calendar.getMinOfHour(time))).append(":")
                .append(String.format("%02d", calendar.getSecOfMin(time))).append(".")
                .append(String.format("%03d", calendar.getMillisOfSec(time))).append("Z");

        ps.print("\")");
    }

}