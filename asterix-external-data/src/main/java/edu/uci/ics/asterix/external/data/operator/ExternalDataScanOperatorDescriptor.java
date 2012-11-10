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
package edu.uci.ics.asterix.external.data.operator;

import java.util.Map;

import edu.uci.ics.asterix.external.adapter.factory.IExternalDatasetAdapterFactory;
import edu.uci.ics.asterix.external.dataset.adapter.IDatasourceAdapter;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.api.application.ICCApplicationContext;
import edu.uci.ics.hyracks.api.constraints.IConstraintAcceptor;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;
import edu.uci.ics.hyracks.api.dataflow.IOperatorNodePushable;
import edu.uci.ics.hyracks.api.dataflow.value.IRecordDescriptorProvider;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.dataflow.std.base.AbstractSingleActivityOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.base.AbstractUnaryOutputSourceOperatorNodePushable;

public class ExternalDataScanOperatorDescriptor extends AbstractSingleActivityOperatorDescriptor {
    private static final long serialVersionUID = 1L;

    private final String adapterFactory;
    private final Map<String, String> adapterConfiguration;
    private final IAType atype;
    private IExternalDatasetAdapterFactory datasourceAdapterFactory;

    public ExternalDataScanOperatorDescriptor(JobSpecification spec, String adapter, Map<String, String> arguments,
            IAType atype, RecordDescriptor rDesc) {
        super(spec, 0, 1);
        recordDescriptors[0] = rDesc;
        this.adapterFactory = adapter;
        this.adapterConfiguration = arguments;
        this.atype = atype;
    }

    @Override
    public void contributeSchedulingConstraints(IConstraintAcceptor constraintAcceptor, ICCApplicationContext appCtx) {

        /*
        Comment: The following code is commented out. This is because constraints are being set at compile time so that they can 
        be propagated to upstream Asterix operators. Hyracks has to provide a way to propagate constraints to upstream operators.
        Once it is there, we will uncomment the following code.  
        
        AlgebricksPartitionConstraint constraint = datasourceReadAdapter.getPartitionConstraint();
        switch (constraint.getPartitionConstraintType()) {
            case ABSOLUTE:
                String[] locations = ((AlgebricksAbsolutePartitionConstraint) constraint).getLocations();
                for (int i = 0; i < locations.length; ++i) {
                    constraintAcceptor.addConstraint(new Constraint(new PartitionLocationExpression(this.odId, i),
                            new ConstantExpression(locations[i])));
                }
                constraintAcceptor.addConstraint(new Constraint(new PartitionCountExpression(this.odId),
                        new ConstantExpression(locations.length)));

                break;
            case COUNT:
                constraintAcceptor.addConstraint(new Constraint(new PartitionCountExpression(this.odId),
                        new ConstantExpression(((AlgebricksCountPartitionConstraint) constraint).getCount())));
                break;
            default:
                throw new IllegalStateException(" Constraint type :" + constraint.getPartitionConstraintType()
                        + " not supported");

        }*/

    }

    public IOperatorNodePushable createPushRuntime(final IHyracksTaskContext ctx,
            IRecordDescriptorProvider recordDescProvider, final int partition, int nPartitions)
            throws HyracksDataException {
        try {
            datasourceAdapterFactory = (IExternalDatasetAdapterFactory) Class.forName(adapterFactory).newInstance();
        } catch (Exception e) {
            throw new HyracksDataException("initialization of adapter failed", e);
        }
        return new AbstractUnaryOutputSourceOperatorNodePushable() {
            @Override
            public void initialize() throws HyracksDataException {
                writer.open();
                IDatasourceAdapter adapter = null;
                try {
                    adapter = ((IExternalDatasetAdapterFactory) datasourceAdapterFactory).createAdapter(
                            adapterConfiguration, atype);
                    adapter.initialize(ctx);
                    adapter.start(partition, writer);
                } catch (Exception e) {
                    throw new HyracksDataException("exception during reading from external data source", e);
                } finally {
                    writer.close();
                }
            }
        };
    }

}
