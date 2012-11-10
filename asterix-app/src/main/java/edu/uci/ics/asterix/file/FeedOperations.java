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
package edu.uci.ics.asterix.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.feed.comm.AlterFeedMessage;
import edu.uci.ics.asterix.feed.comm.FeedMessage;
import edu.uci.ics.asterix.feed.comm.IFeedMessage;
import edu.uci.ics.asterix.feed.comm.IFeedMessage.MessageType;
import edu.uci.ics.asterix.metadata.declared.AqlMetadataProvider;
import edu.uci.ics.asterix.metadata.entities.Dataset;
import edu.uci.ics.asterix.metadata.entities.FeedDatasetDetails;
import edu.uci.ics.asterix.translator.CompiledStatements.CompiledControlFeedStatement;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraintHelper;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.Pair;
import edu.uci.ics.hyracks.api.dataflow.IOperatorDescriptor;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.dataflow.std.connectors.OneToOneConnectorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.misc.NullSinkOperatorDescriptor;

public class FeedOperations {

    private static final Logger LOGGER = Logger.getLogger(IndexOperations.class.getName());

    public static JobSpecification buildControlFeedJobSpec(CompiledControlFeedStatement controlFeedStatement,
            AqlMetadataProvider metadataProvider) throws AsterixException, AlgebricksException {
        switch (controlFeedStatement.getOperationType()) {
            case ALTER:
            case SUSPEND:
            case RESUME:
            case END: {
                return createSendMessageToFeedJobSpec(controlFeedStatement, metadataProvider);
            }
            default: {
                throw new AsterixException("Unknown Operation Type: " + controlFeedStatement.getOperationType());
            }

        }
    }

    private static JobSpecification createSendMessageToFeedJobSpec(CompiledControlFeedStatement controlFeedStatement,
            AqlMetadataProvider metadataProvider) throws AsterixException {
        String dataverseName = controlFeedStatement.getDataverseName() == null ? metadataProvider
                .getDefaultDataverseName() : controlFeedStatement.getDataverseName();
        String datasetName = controlFeedStatement.getDatasetName();
        String datasetPath = dataverseName + File.separator + datasetName;

        LOGGER.info(" DATASETPATH: " + datasetPath);

        Dataset dataset;
        try {
            dataset = metadataProvider.findDataset(dataverseName, datasetName);
        } catch (AlgebricksException e) {
            throw new AsterixException(e);
        }
        if (dataset == null) {
            throw new AsterixException("FEED DATASET: No metadata for dataset " + datasetName);
        }
        if (dataset.getDatasetType() != DatasetType.FEED) {
            throw new AsterixException("Operation not support for dataset type  " + dataset.getDatasetType());
        }

        JobSpecification spec = new JobSpecification();
        IOperatorDescriptor feedMessenger;
        AlgebricksPartitionConstraint messengerPc;

        List<IFeedMessage> feedMessages = new ArrayList<IFeedMessage>();
        switch (controlFeedStatement.getOperationType()) {
            case SUSPEND:
                feedMessages.add(new FeedMessage(MessageType.SUSPEND));
                break;
            case END:
                feedMessages.add(new FeedMessage(MessageType.STOP));
                break;
            case RESUME:
                feedMessages.add(new FeedMessage(MessageType.RESUME));
                break;
            case ALTER:
                feedMessages.add(new AlterFeedMessage(controlFeedStatement.getProperties()));
                break;
        }

        try {
            Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> p = metadataProvider.buildFeedMessengerRuntime(
                    metadataProvider, spec, (FeedDatasetDetails) dataset.getDatasetDetails(),
                    metadataProvider.getDefaultDataverseName(), datasetName, feedMessages);
            feedMessenger = p.first;
            messengerPc = p.second;
        } catch (AlgebricksException e) {
            throw new AsterixException(e);
        }

        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, feedMessenger, messengerPc);

        NullSinkOperatorDescriptor nullSink = new NullSinkOperatorDescriptor(spec);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, nullSink, messengerPc);

        spec.connect(new OneToOneConnectorDescriptor(spec), feedMessenger, 0, nullSink, 0);

        spec.addRoot(nullSink);
        return spec;

    }
}
