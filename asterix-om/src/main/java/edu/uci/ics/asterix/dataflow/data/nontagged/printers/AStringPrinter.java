package edu.uci.ics.asterix.dataflow.data.nontagged.printers;

import java.io.IOException;
import java.io.PrintStream;

import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.data.IPrinter;
import edu.uci.ics.hyracks.algebricks.data.utils.WriteValueTools;

public class AStringPrinter implements IPrinter {

    private static final long serialVersionUID = 1L;
    public static final AStringPrinter INSTANCE = new AStringPrinter();

    @Override
    public void init() {

    }

    @Override
    public void print(byte[] b, int s, int l, PrintStream ps) throws AlgebricksException {
        try {
            WriteValueTools.writeUTF8String(b, s + 1, l - 1, ps);
        } catch (IOException e) {
            throw new AlgebricksException(e);
        }
    }
}