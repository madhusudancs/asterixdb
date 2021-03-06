package edu.uci.ics.asterix.common.annotations;

import java.io.Serializable;

public class RecordDataGenAnnotation implements IRecordTypeAnnotation, Serializable {

    private final IRecordFieldDataGen[] declaredFieldsDatagen;
    private final UndeclaredFieldsDataGen undeclaredFieldsDataGen;

    public RecordDataGenAnnotation(IRecordFieldDataGen[] declaredFieldsDatagen,
            UndeclaredFieldsDataGen undeclaredFieldsDataGen) {
        this.declaredFieldsDatagen = declaredFieldsDatagen;
        this.undeclaredFieldsDataGen = undeclaredFieldsDataGen;
    }

    @Override
    public Kind getKind() {
        return Kind.RECORD_DATA_GEN;
    }

    public IRecordFieldDataGen[] getDeclaredFieldsDatagen() {
        return declaredFieldsDatagen;
    }

    public UndeclaredFieldsDataGen getUndeclaredFieldsDataGen() {
        return undeclaredFieldsDataGen;
    }

}
