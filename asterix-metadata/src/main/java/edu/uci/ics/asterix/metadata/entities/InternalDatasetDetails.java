/*
 * Copyright 2009-2011 by The Regents of the University of California
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
package edu.uci.ics.asterix.metadata.entities;

import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

import edu.uci.ics.asterix.builders.IARecordBuilder;
import edu.uci.ics.asterix.builders.OrderedListBuilder;
import edu.uci.ics.asterix.builders.RecordBuilder;
import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.metadata.IDatasetDetails;
import edu.uci.ics.asterix.metadata.bootstrap.MetadataRecordTypes;
import edu.uci.ics.asterix.om.base.AMutableString;
import edu.uci.ics.asterix.om.base.AString;
import edu.uci.ics.asterix.om.types.AOrderedListType;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.data.std.util.ArrayBackedValueStorage;

public class InternalDatasetDetails implements IDatasetDetails {

    private static final long serialVersionUID = 1L;

    public enum FileStructure {
        BTREE
    };

    public enum PartitioningStrategy {
        HASH
    };

    protected final FileStructure fileStructure;
    protected final PartitioningStrategy partitioningStrategy;
    protected final List<String> partitioningKeys;
    protected final List<String> primaryKeys;
    protected final String nodeGroupName;

    public InternalDatasetDetails(FileStructure fileStructure, PartitioningStrategy partitioningStrategy,
            List<String> partitioningKey, List<String> primaryKey, String groupName) {
        this.fileStructure = fileStructure;
        this.partitioningStrategy = partitioningStrategy;
        this.partitioningKeys = partitioningKey;
        this.primaryKeys = primaryKey;
        this.nodeGroupName = groupName;
    }

    public String getNodeGroupName() {
        return nodeGroupName;
    }

    public List<String> getPartitioningKey() {
        return partitioningKeys;
    }

    public List<String> getPrimaryKey() {
        return primaryKeys;
    }

    public FileStructure getFileStructure() {
        return fileStructure;
    }

    public PartitioningStrategy getPartitioningStrategy() {
        return partitioningStrategy;
    }

    @Override
    public DatasetType getDatasetType() {
        return DatasetType.INTERNAL;
    }

    @Override
    public void writeDatasetDetailsRecordType(DataOutput out) throws HyracksDataException {

        IARecordBuilder internalRecordBuilder = new RecordBuilder();
        OrderedListBuilder listBuilder = new OrderedListBuilder();
        ArrayBackedValueStorage fieldValue = new ArrayBackedValueStorage();
        ArrayBackedValueStorage itemValue = new ArrayBackedValueStorage();
        internalRecordBuilder.reset(MetadataRecordTypes.INTERNAL_DETAILS_RECORDTYPE);
        AMutableString aString = new AMutableString("");
        ISerializerDeserializer<AString> stringSerde = AqlSerializerDeserializerProvider.INSTANCE
                .getSerializerDeserializer(BuiltinType.ASTRING);

        // write field 0
        fieldValue.reset();
        aString.setValue(getFileStructure().toString());
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        internalRecordBuilder.addField(MetadataRecordTypes.INTERNAL_DETAILS_ARECORD_FILESTRUCTURE_FIELD_INDEX,
                fieldValue);

        // write field 1
        fieldValue.reset();
        aString.setValue(getPartitioningStrategy().toString());
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        internalRecordBuilder.addField(MetadataRecordTypes.INTERNAL_DETAILS_ARECORD_PARTITIONSTRATEGY_FIELD_INDEX,
                fieldValue);

        // write field 2
        listBuilder
                .reset((AOrderedListType) MetadataRecordTypes.INTERNAL_DETAILS_RECORDTYPE.getFieldTypes()[MetadataRecordTypes.INTERNAL_DETAILS_ARECORD_PARTITIONKEY_FIELD_INDEX]);
        for (String field : partitioningKeys) {
            itemValue.reset();
            aString.setValue(field);
            stringSerde.serialize(aString, itemValue.getDataOutput());
            listBuilder.addItem(itemValue);
        }
        fieldValue.reset();
        listBuilder.write(fieldValue.getDataOutput(), true);
        internalRecordBuilder.addField(MetadataRecordTypes.INTERNAL_DETAILS_ARECORD_PARTITIONKEY_FIELD_INDEX,
                fieldValue);

        // write field 3
        listBuilder
                .reset((AOrderedListType) MetadataRecordTypes.INTERNAL_DETAILS_RECORDTYPE.getFieldTypes()[MetadataRecordTypes.INTERNAL_DETAILS_ARECORD_PRIMARYKEY_FIELD_INDEX]);
        for (String field : primaryKeys) {
            itemValue.reset();
            aString.setValue(field);
            stringSerde.serialize(aString, itemValue.getDataOutput());
            listBuilder.addItem(itemValue);
        }
        fieldValue.reset();
        listBuilder.write(fieldValue.getDataOutput(), true);
        internalRecordBuilder.addField(MetadataRecordTypes.INTERNAL_DETAILS_ARECORD_PRIMARYKEY_FIELD_INDEX, fieldValue);

        // write field 4
        fieldValue.reset();
        aString.setValue(getNodeGroupName());
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        internalRecordBuilder.addField(MetadataRecordTypes.INTERNAL_DETAILS_ARECORD_GROUPNAME_FIELD_INDEX, fieldValue);

        try {
            internalRecordBuilder.write(out, true);
        } catch (IOException ioe) {
            throw new HyracksDataException(ioe);
        }
    }

}
