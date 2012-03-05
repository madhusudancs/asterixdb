package edu.uci.ics.asterix.runtime.operators.file;

import java.io.InputStream;

import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.ABoolean;
import edu.uci.ics.asterix.om.base.ADouble;
import edu.uci.ics.asterix.om.base.AFloat;
import edu.uci.ics.asterix.om.base.AInt16;
import edu.uci.ics.asterix.om.base.AInt32;
import edu.uci.ics.asterix.om.base.AInt64;
import edu.uci.ics.asterix.om.base.AInt8;
import edu.uci.ics.asterix.om.base.AMutableDouble;
import edu.uci.ics.asterix.om.base.AMutableFloat;
import edu.uci.ics.asterix.om.base.AMutableInt16;
import edu.uci.ics.asterix.om.base.AMutableInt32;
import edu.uci.ics.asterix.om.base.AMutableInt64;
import edu.uci.ics.asterix.om.base.AMutableInt8;
import edu.uci.ics.asterix.om.base.AMutableString;
import edu.uci.ics.asterix.om.base.ANull;
import edu.uci.ics.asterix.om.base.AString;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.hyracks.api.comm.IFrameWriter;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParser;

public abstract class AbstractTupleParser implements ITupleParser {

	// Mutable Types..
	protected AMutableInt8 aInt8 = new AMutableInt8((byte) 0);
	protected AMutableInt16 aInt16 = new AMutableInt16((short) 0);
	protected AMutableInt32 aInt32 = new AMutableInt32(0);
	protected AMutableInt64 aInt64 = new AMutableInt64(0);
	protected AMutableDouble aDouble = new AMutableDouble(0);
	protected AMutableFloat aFloat = new AMutableFloat(0);
	protected AMutableString aString = new AMutableString("");
	protected AMutableString aStringFieldName = new AMutableString("");

	// Serializers
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<ADouble> doubleSerde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.ADOUBLE);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<AString> stringSerde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.ASTRING);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<AFloat> floatSerde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.AFLOAT);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<AInt8> int8Serde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.AINT8);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<AInt16> int16Serde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.AINT16);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<AInt32> int32Serde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.AINT32);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<AInt64> int64Serde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.AINT64);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<ABoolean> booleanSerde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.ABOOLEAN);
	@SuppressWarnings("unchecked")
	protected ISerializerDeserializer<ANull> nullSerde = AqlSerializerDeserializerProvider.INSTANCE
			.getSerializerDeserializer(BuiltinType.ANULL);

	
	@Override
	public abstract  void parse(InputStream in, IFrameWriter writer) throws HyracksDataException;
}
