package edu.uci.ics.asterix.dataflow.data.nontagged.printers.json;

import java.io.PrintStream;

import edu.uci.ics.asterix.formats.nontagged.AqlJSONPrinterFactoryProvider;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.AUnionType;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.data.IPrinter;
import edu.uci.ics.hyracks.algebricks.data.IPrinterFactory;

public class ANullableFieldPrinterFactory implements IPrinterFactory {

    private static final long serialVersionUID = 1L;

    private AUnionType unionType;

    public ANullableFieldPrinterFactory(AUnionType unionType) {
        this.unionType = unionType;
    }

    @Override
    public IPrinter createPrinter() {
        return new IPrinter() {
            private IPrinter nullPrinter;
            private IPrinter fieldPrinter;

            @Override
            public void init() throws AlgebricksException {
                nullPrinter = (AqlJSONPrinterFactoryProvider.INSTANCE.getPrinterFactory(BuiltinType.ANULL))
                        .createPrinter();
                fieldPrinter = (AqlJSONPrinterFactoryProvider.INSTANCE.getPrinterFactory(unionType.getUnionList()
                        .get(1))).createPrinter();
            }

            @Override
            public void print(byte[] b, int s, int l, PrintStream ps) throws AlgebricksException {
                fieldPrinter.init();
                if (b[s] == ATypeTag.NULL.serialize())
                    nullPrinter.print(b, s, l, ps);
                else
                    fieldPrinter.print(b, s, l, ps);
            }

        };
    }

}
