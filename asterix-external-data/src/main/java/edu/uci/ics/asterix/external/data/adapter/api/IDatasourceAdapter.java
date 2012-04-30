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
package edu.uci.ics.asterix.external.data.adapter.api;

import java.io.Serializable;
import java.util.Map;

import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.core.api.constraints.AlgebricksPartitionConstraint;
import edu.uci.ics.hyracks.api.client.NodeControllerInfo;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;

/**
 * A super interface implemented by a data source adapter. An adapter can be a
 * pull based or push based. This interface provides all common APIs that need
 * to be implemented by each adapter irrespective of the the kind of
 * adapter(pull or push).
 */
public interface IDatasourceAdapter extends Serializable {

    /**
     * Represents the kind of data exchange that happens between the adapter and
     * the external data source. The data exchange can be either pull based or
     * push based. In the former case (pull), the request for data transfer is
     * initiated by the adapter. In the latter case (push) the adapter is
     * required to submit an initial request to convey intent for data.
     * Subsequently all data transfer requests are initiated by the external
     * data source.
     */
    public enum AdapterDataFlowType {
        PULL,
        PUSH
    }

    /**
     * An adapter can be used to read from an external data source and may also
     * allow writing to the external data source. This enum type indicates the
     * kind of operations supported by the adapter.
     * 
     * @caller Compiler uses this method to assert the validity of an operation
     *         on an external dataset. The type of adapter associated with an
     *         external dataset determines the set of valid operations allowed
     *         on the dataset.
     */
    public enum AdapterType {
        READ,
        WRITE,
        READ_WRITE
    }

    /**
     * An adapter can be a pull or a push based adapter. This method returns the
     * kind of adapter, that is whether it is a pull based adapter or a push
     * based adapter.
     * 
     * @caller Compiler or wrapper operator: Compiler uses this API to choose
     *         the right wrapper (push-based) operator that wraps around the
     *         adapter and provides an iterator interface. If we decide to form
     *         a single operator that handles both pull and push based adapter
     *         kinds, then this method will be used by the wrapper operator for
     *         switching between the logic for interacting with a pull based
     *         adapter versus a push based adapter.
     * @return AdapterDataFlowType
     */
    public AdapterDataFlowType getAdapterDataFlowType();

    /**
     * Returns the type of adapter indicating if the adapter can be used for
     * reading from an external data source or writing to an external data
     * source or can be used for both purposes.
     * 
     * @Caller: Compiler: The compiler uses this API to verify if an operation
     *          is supported by the adapter. For example, an write query against
     *          an external dataset will not compile successfully if the
     *          external dataset was declared with a read_only adapter.
     * @see AdapterType
     * @return
     */
    public AdapterType getAdapterType();

    /**
     * Each adapter instance is configured with a set of parameters that are
     * key-value pairs. When creating an external or a feed dataset, an adapter
     * instance is used in conjunction with a set of configuration parameters
     * for the adapter instance. The configuration parameters are stored
     * internally with the adapter and can be retrieved using this API.
     * 
     * @param propertyKey
     * @return String the value corresponding to the configuration parameter
     *         represented by the key- attributeKey.
     */
    public String getAdapterProperty(String propertyKey);

    /**
     * Allows setting a configuration property of the adapter with a specified
     * value.
     * 
     * @caller Used by the wrapper operator to modify the behavior of the
     *         adapter, if required.
     * @param property
     *            the property to be set
     * @param value
     *            the value for the property
     */
    public void setAdapterProperty(String property, String value);

    /**
     * Configures the IDatasourceAdapter instance.
     * 
     * @caller Scenario 1) Called during compilation of DDL statement that
     *         creates a Feed dataset and associates the adapter with the
     *         dataset. The (key,value) configuration parameters provided as
     *         part of the DDL statement are collected by the compiler and
     *         passed on to this method. The adapter may as part of
     *         configuration connect with the external data source and determine
     *         the IAType associated with data residing with the external
     *         datasource.
     *         Scenario 2) An adapter instance is created by an ASTERIX operator
     *         that wraps around the adapter instance. The operator, as part of
     *         its initialization invokes the configure method. The (key,value)
     *         configuration parameters are passed on to the operator by the
     *         compiler. Subsequent to the invocation, the wrapping operator
     *         obtains the partition constraints (if any). In addition, in the
     *         case of a read adapter, the wrapping operator obtains the output
     *         ASTERIX type associated with the data that will be output from
     *         the adapter.
     * @param arguments
     *            A map with key-value pairs that contains the configuration
     *            parameters for the adapter. The arguments are obtained from
     *            the metadata. Recall that the DDL to create an external
     *            dataset or a feed dataset requires using an adapter and
     *            providing all arguments as a set of (key,value) pairs. These
     *            arguments are put into the metadata.
     */
    public void configure(Map<String, String> arguments, IAType atype)
            throws Exception;

    /**
     * Returns a list of partition constraints. A partition constraint can be a
     * requirement to execute at a particular location or could be cardinality
     * constraints indicating the number of instances that need to run in
     * parallel. example, a IDatasourceAdapter implementation written for data
     * residing on the local file system of a node cannot run on any other node
     * and thus has a location partition constraint. The location partition
     * constraint can be expressed as a node IP address or a node controller id.
     * In the former case, the IP address is translated to a node controller id
     * running on the node with the given IP address.
     * 
     * @Caller The wrapper operator configures its partition constraints from
     *         the constraints obtained from the adapter.
     */
    public AlgebricksPartitionConstraint getPartitionConstraint();

    /**
     * Allows the adapter to establish connection with the external data source
     * expressing intent for data and providing any configuration parameters
     * required by the external data source for the transfer of data. This
     * method does not result in any data transfer, but is a prerequisite for
     * any subsequent data transfer to happen between the external data source
     * and the adapter.
     * 
     * @caller This method is called by the wrapping ASTERIX operator that
     * @param ctx
     * @throws Exception
     */
    public void initialize(IHyracksTaskContext ctx) throws Exception;
}
