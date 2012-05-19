/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.asterix.runtime.accessors.cast;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import edu.uci.ics.asterix.builders.RecordBuilder;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.dataflow.data.nontagged.AqlNullWriterFactory;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.AUnionType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.om.util.NonTaggedFormatUtil;
import edu.uci.ics.asterix.runtime.accessors.AFlatValueAccessor;
import edu.uci.ics.asterix.runtime.accessors.ARecordAccessor;
import edu.uci.ics.asterix.runtime.accessors.base.DefaultOpenFieldType;
import edu.uci.ics.asterix.runtime.accessors.base.IBinaryAccessor;
import edu.uci.ics.asterix.runtime.util.ResettableByteArrayOutputStream;
import edu.uci.ics.hyracks.algebricks.common.utils.Triple;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparator;
import edu.uci.ics.hyracks.api.dataflow.value.INullWriter;
import edu.uci.ics.hyracks.data.std.accessors.PointableBinaryComparatorFactory;
import edu.uci.ics.hyracks.data.std.primitive.UTF8StringPointable;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IValueReference;

class ARecordCaster {

    // describe closed fields in the required type
    private int[] fieldPermutation;
    private boolean[] optionalFields;

    // describe fields (open or not) in the input records
    private boolean[] openFields;
    private int[] fieldNamesSortedIndex;

    private List<IBinaryAccessor> reqFieldNames = new ArrayList<IBinaryAccessor>();
    private int[] reqFieldNamesSortedIndex;
    private List<IBinaryAccessor> reqFieldTypeTags = new ArrayList<IBinaryAccessor>();
    private ARecordType cachedReqType = null;

    private byte[] buffer = new byte[32768];
    private ResettableByteArrayOutputStream bos = new ResettableByteArrayOutputStream();
    private DataOutputStream dos = new DataOutputStream(bos);

    private RecordBuilder recBuilder = new RecordBuilder();
    private IBinaryAccessor nullReference = AFlatValueAccessor.FACTORY.createElement(null);
    private IBinaryAccessor nullTypeTag = AFlatValueAccessor.FACTORY.createElement(null);

    private int numInputFields = 0;
    private IBinaryComparator fieldNameComparator = PointableBinaryComparatorFactory.of(UTF8StringPointable.FACTORY)
            .createBinaryComparator();

    private byte[] outputBuffer = new byte[32768];
    private ResettableByteArrayOutputStream outputBos = new ResettableByteArrayOutputStream();
    private DataOutputStream outputDos = new DataOutputStream(outputBos);

    private IBinaryAccessor fieldTempReference = AFlatValueAccessor.FACTORY.createElement(null);
    private Triple<IBinaryAccessor, IAType, Boolean> nestedVisitorArg = new Triple<IBinaryAccessor, IAType, Boolean>(
            fieldTempReference, null, null);

    public ARecordCaster() {
        try {
            bos.setByteArray(buffer, 0);
            int start = bos.size();
            INullWriter nullWriter = AqlNullWriterFactory.INSTANCE.createNullWriter();
            nullWriter.writeNull(dos);
            int end = bos.size();
            nullReference.reset(buffer, start, end - start);
            start = bos.size();
            dos.write(ATypeTag.NULL.serialize());
            end = bos.size();
            nullTypeTag.reset(buffer, start, end);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void castRecord(ARecordAccessor recordAccessor, IBinaryAccessor resultAccessor, ARecordType reqType,
            ACastVisitor visitor) throws IOException, AsterixException {
        List<IBinaryAccessor> fieldNames = recordAccessor.getFieldNames();
        List<IBinaryAccessor> fieldTypeTags = recordAccessor.getFieldTypeTags();
        List<IBinaryAccessor> fieldValues = recordAccessor.getFieldValues();
        numInputFields = fieldNames.size();

        if (openFields == null || numInputFields > openFields.length) {
            openFields = new boolean[numInputFields];
            fieldNamesSortedIndex = new int[numInputFields];
        }
        if (cachedReqType == null || !reqType.equals(cachedReqType)) {
            loadRequiredType(reqType);
        }

        // clear the previous states
        reset();
        matchClosedPart(fieldNames, fieldTypeTags, fieldValues);
        writeOutput(fieldNames, fieldTypeTags, fieldValues, outputDos, visitor);
        resultAccessor.reset(outputBuffer, 0, outputBos.size());
    }

    private void reset() {
        for (int i = 0; i < numInputFields; i++)
            openFields[i] = true;
        for (int i = 0; i < fieldPermutation.length; i++)
            fieldPermutation[i] = -1;
        for (int i = 0; i < numInputFields; i++)
            fieldNamesSortedIndex[i] = i;
        outputBos.setByteArray(outputBuffer, 0);
    }

    private void loadRequiredType(ARecordType reqType) throws IOException {
        reqFieldNames.clear();
        reqFieldTypeTags.clear();

        cachedReqType = reqType;
        int numSchemaFields = reqType.getFieldTypes().length;
        IAType[] fieldTypes = reqType.getFieldTypes();
        String[] fieldNames = reqType.getFieldNames();
        fieldPermutation = new int[numSchemaFields];
        optionalFields = new boolean[numSchemaFields];
        for (int i = 0; i < optionalFields.length; i++)
            optionalFields[i] = false;

        bos.setByteArray(buffer, nullReference.getStartIndex() + nullReference.getLength());
        for (int i = 0; i < numSchemaFields; i++) {
            ATypeTag ftypeTag = fieldTypes[i].getTypeTag();
            String fname = fieldNames[i];

            // add type tag pointable
            if (fieldTypes[i].getTypeTag() == ATypeTag.UNION
                    && NonTaggedFormatUtil.isOptionalField((AUnionType) fieldTypes[i])) {
                // optional field: add the embedded non-null type tag
                ftypeTag = ((AUnionType) fieldTypes[i]).getUnionList()
                        .get(NonTaggedFormatUtil.OPTIONAL_TYPE_INDEX_IN_UNION_LIST).getTypeTag();
                optionalFields[i] = true;
            }
            int tagStart = bos.size();
            dos.writeByte(ftypeTag.serialize());
            int tagEnd = bos.size();
            IBinaryAccessor typeTagPointable = AFlatValueAccessor.FACTORY.createElement(null);
            typeTagPointable.reset(buffer, tagStart, tagEnd - tagStart);
            reqFieldTypeTags.add(typeTagPointable);

            // add type name pointable (including a string type tag)
            int nameStart = bos.size();
            dos.write(ATypeTag.STRING.serialize());
            dos.writeUTF(fname);
            int nameEnd = bos.size();
            IBinaryAccessor typeNamePointable = AFlatValueAccessor.FACTORY.createElement(null);
            typeNamePointable.reset(buffer, nameStart, nameEnd - nameStart);
            reqFieldNames.add(typeNamePointable);
        }

        reqFieldNamesSortedIndex = new int[reqFieldNames.size()];
        for (int i = 0; i < reqFieldNamesSortedIndex.length; i++)
            reqFieldNamesSortedIndex[i] = i;
        // sort the field name index
        quickSort(reqFieldNamesSortedIndex, reqFieldNames, 0, reqFieldNamesSortedIndex.length - 1);
    }

    private void matchClosedPart(List<IBinaryAccessor> fieldNames, List<IBinaryAccessor> fieldTypeTags,
            List<IBinaryAccessor> fieldValues) {
        // sort-merge based match
        quickSort(fieldNamesSortedIndex, fieldNames, 0, numInputFields - 1);
        int fnStart = 0;
        int reqFnStart = 0;
        while (fnStart < numInputFields && reqFnStart < reqFieldNames.size()) {
            int fnPos = fieldNamesSortedIndex[fnStart];
            int reqFnPos = reqFieldNamesSortedIndex[reqFnStart];
            int c = compare(fieldNames.get(fnPos), reqFieldNames.get(reqFnPos));
            if (c == 0) {
                IBinaryAccessor fieldTypeTag = fieldTypeTags.get(fnPos);
                IBinaryAccessor reqFieldTypeTag = reqFieldTypeTags.get(reqFnPos);
                if (fieldTypeTag.equals(reqFieldTypeTag) || (
                // match the null type of optional field
                        optionalFields[reqFnPos] && fieldTypeTag.equals(nullTypeTag))) {
                    fieldPermutation[reqFnPos] = fnPos;
                    openFields[fnPos] = false;
                }
                fnStart++;
                reqFnStart++;
            }
            if (c > 0)
                reqFnStart++;
            if (c < 0)
                fnStart++;
        }

        // check unmatched fields in the input type
        for (int i = 0; i < openFields.length; i++) {
            if (openFields[i] == true && !cachedReqType.isOpen())
                throw new IllegalStateException("type mismatch: including extra closed fields");
        }

        // check unmatched fields in the required type
        for (int i = 0; i < fieldPermutation.length; i++) {
            if (fieldPermutation[i] < 0) {
                IAType t = cachedReqType.getFieldTypes()[i];
                if (!(t.getTypeTag() == ATypeTag.UNION && NonTaggedFormatUtil.isOptionalField((AUnionType) t))) {
                    // no matched field in the input for a required closed field
                    throw new IllegalStateException("type mismatch: miss a required closed field");
                }
            }
        }
    }

    private void writeOutput(List<IBinaryAccessor> fieldNames, List<IBinaryAccessor> fieldTypeTags,
            List<IBinaryAccessor> fieldValues, DataOutput output, ACastVisitor visitor) throws IOException,
            AsterixException {
        // reset the states of the record builder
        recBuilder.reset(cachedReqType);
        recBuilder.init();

        // write the closed part
        for (int i = 0; i < fieldPermutation.length; i++) {
            int pos = fieldPermutation[i];
            IBinaryAccessor field;
            if (pos >= 0) {
                field = fieldValues.get(pos);
            } else {
                field = nullReference;
            }
            IAType fType = cachedReqType.getFieldTypes()[i];
            nestedVisitorArg.second = fType;

            // recursively casting, the result of casting can always be thought
            // as flat
            if (optionalFields[i]) {
                nestedVisitorArg.second = ((AUnionType) fType).getUnionList().get(
                        NonTaggedFormatUtil.OPTIONAL_TYPE_INDEX_IN_UNION_LIST);
            }
            field.accept(visitor, nestedVisitorArg);
            recBuilder.addField(i, nestedVisitorArg.first);
            //reset the req type
            nestedVisitorArg.second = null;
        }

        // write the open part
        for (int i = 0; i < numInputFields; i++) {
            if (openFields[i]) {
                IBinaryAccessor name = fieldNames.get(i);
                IBinaryAccessor field = fieldValues.get(i);
                IBinaryAccessor fieldTypeTag = fieldTypeTags.get(i);

                ATypeTag typeTag = EnumDeserializer.ATYPETAGDESERIALIZER
                        .deserialize(fieldTypeTag.getBytes()[fieldTypeTag.getStartIndex()]);
                if (typeTag.equals(ATypeTag.RECORD))
                    nestedVisitorArg.second = DefaultOpenFieldType.NESTED_OPEN_RECORD_TYPE;
                field.accept(visitor, nestedVisitorArg);
                recBuilder.addField(name, nestedVisitorArg.first);
                
                //reset the req type
                nestedVisitorArg.second = null;
            }
        }
        recBuilder.write(output, true);
    }

    private void quickSort(int[] index, List<IBinaryAccessor> names, int start, int end) {
        if (end <= start)
            return;
        int i = partition(index, names, start, end);
        quickSort(index, names, start, i - 1);
        quickSort(index, names, i + 1, end);
    }

    private int partition(int[] index, List<IBinaryAccessor> names, int left, int right) {
        int i = left - 1;
        int j = right;
        while (true) {
            // grow from the left
            while (compare(names.get(index[++i]), names.get(index[right])) < 0)
                ;
            // lower from the right
            while (compare(names.get(index[right]), names.get(index[--j])) < 0)
                if (j == left)
                    break;
            if (i >= j)
                break;
            // swap i and j
            swap(index, i, j);
        }
        // swap i and right
        swap(index, i, right); // swap with partition element
        return i;
    }

    private void swap(int[] array, int i, int j) {
        int temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

    private int compare(IValueReference a, IValueReference b) {
        // start+1 and len-1 due to the type tag
        return fieldNameComparator.compare(a.getBytes(), a.getStartIndex() + 1, a.getLength() - 1, b.getBytes(),
                b.getStartIndex() + 1, b.getLength() - 1);
    }
}
