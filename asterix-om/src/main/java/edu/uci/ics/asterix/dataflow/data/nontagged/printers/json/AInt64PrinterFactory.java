package edu.uci.ics.asterix.dataflow.data.nontagged.printers.json;

import edu.uci.ics.hyracks.algebricks.data.IPrinter;
import edu.uci.ics.hyracks.algebricks.data.IPrinterFactory;

public class AInt64PrinterFactory implements IPrinterFactory {

    private static final long serialVersionUID = 1L;
    public static final AInt64PrinterFactory INSTANCE = new AInt64PrinterFactory();

    @Override
    public IPrinter createPrinter() {
        return AInt64Printer.INSTANCE;
    }

}