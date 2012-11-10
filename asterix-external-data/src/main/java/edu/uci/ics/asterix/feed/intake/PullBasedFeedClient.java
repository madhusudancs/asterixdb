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
package edu.uci.ics.asterix.feed.intake;

import java.io.DataOutput;
import java.io.IOException;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.dataflow.data.nontagged.serde.ARecordSerializerDeserializer;
import edu.uci.ics.asterix.om.base.AMutableRecord;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;

public abstract class PullBasedFeedClient implements IPullBasedFeedClient {

	protected ARecordSerializerDeserializer recordSerDe;
	protected AMutableRecord mutableRecord;
	protected boolean messageReceived;

	public abstract boolean setNextRecord() throws Exception;

	@Override
	public boolean nextTuple(DataOutput dataOutput) throws AsterixException {
		try {
			boolean newData = setNextRecord();
			if (newData) {
				IAType t = mutableRecord.getType();
				ATypeTag tag = t.getTypeTag();
				try {
					dataOutput.writeByte(tag.serialize());
				} catch (IOException e) {
					throw new HyracksDataException(e);
				}
				recordSerDe.serialize(mutableRecord, dataOutput);
				return true;
			}
			return false;
		} catch (Exception e) {
			throw new AsterixException(e);
		}

	}

	/*
	 * public void displayFeedRecord() { StringBuilder builder = new
	 * StringBuilder(); int numFields = recordType.getFieldNames().length; for
	 * (int i = 0; i < numFields; i++) {
	 * builder.append(mutableRecord.getValueByPos(i).toString());
	 * builder.append("|"); } }
	 */

	@Override
	public void suspend() throws Exception {
		// TODO Auto-generated method stub

	}

	@Override
	public void resume() throws Exception {
		// TODO Auto-generated method stub

	}

	@Override
	public void stop() throws Exception {
		// TODO Auto-generated method stub

	}

}
