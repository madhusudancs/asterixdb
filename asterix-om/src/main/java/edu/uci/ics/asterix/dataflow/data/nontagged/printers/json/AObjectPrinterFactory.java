package edu.uci.ics.asterix.dataflow.data.nontagged.printers.json;

import edu.uci.ics.hyracks.algebricks.data.IPrinter;
import edu.uci.ics.hyracks.algebricks.data.IPrinterFactory;

public class AObjectPrinterFactory implements IPrinterFactory {

    private static final long serialVersionUID = 1L;
    public static final AObjectPrinterFactory INSTANCE = new AObjectPrinterFactory();

    @Override
    public IPrinter createPrinter() {
        return AObjectPrinter.INSTANCE;
    }

}