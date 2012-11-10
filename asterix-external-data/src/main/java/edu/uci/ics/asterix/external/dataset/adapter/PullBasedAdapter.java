/*
 * Copyright 2009-2012 by The Regents of the University of California
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
package edu.uci.ics.asterix.external.dataset.adapter;

import java.nio.ByteBuffer;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.feed.intake.IPullBasedFeedClient;
import edu.uci.ics.hyracks.api.comm.IFrameWriter;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import edu.uci.ics.hyracks.dataflow.common.comm.io.FrameTupleAppender;
import edu.uci.ics.hyracks.dataflow.common.comm.util.FrameUtils;

public abstract class PullBasedAdapter extends AbstractFeedDatasourceAdapter implements IDatasourceAdapter {

    protected ArrayTupleBuilder tupleBuilder = new ArrayTupleBuilder(1);
    protected IPullBasedFeedClient pullBasedFeedClient;
    private FrameTupleAppender appender;
    private ByteBuffer frame;

    public abstract IPullBasedFeedClient getFeedClient(int partition) throws Exception;

    @Override
    public void start(int partition, IFrameWriter writer) throws Exception {
        appender = new FrameTupleAppender(ctx.getFrameSize());
        frame = ctx.allocateFrame();
        appender.reset(frame, true);

        pullBasedFeedClient = getFeedClient(partition);
        while (true) {
            tupleBuilder.reset();
            try {
                pullBasedFeedClient.nextTuple(tupleBuilder.getDataOutput()); // nextTuple is a blocking call.
            } catch (Exception failureException) {
                try {
                    pullBasedFeedClient.resetOnFailure(failureException);
                    continue;
                } catch (Exception recoveryException) {
                    throw new Exception(recoveryException);
                }

            }
            tupleBuilder.addFieldEndOffset();
            appendTupleToFrame(writer);

        }
    }

    public void resetOnFailure(Exception e) throws AsterixException {
        pullBasedFeedClient.resetOnFailure(e);
        tupleBuilder.reset();
    }

    private void appendTupleToFrame(IFrameWriter writer) throws HyracksDataException {
        if (!appender.append(tupleBuilder.getFieldEndOffsets(), tupleBuilder.getByteArray(), 0, tupleBuilder.getSize())) {
            FrameUtils.flushFrame(frame, writer);
            appender.reset(frame, true);
            if (!appender.append(tupleBuilder.getFieldEndOffsets(), tupleBuilder.getByteArray(), 0,
                    tupleBuilder.getSize())) {
                throw new IllegalStateException();
            }
        }
    }

}
