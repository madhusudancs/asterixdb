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
package edu.uci.ics.asterix.hyracks.bootstrap;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.uci.ics.asterix.api.aqlj.server.NodeDataClientThreadFactory;
import edu.uci.ics.asterix.api.aqlj.server.ThreadedServer;
import edu.uci.ics.asterix.common.context.AsterixAppRuntimeContext;
import edu.uci.ics.asterix.metadata.MetadataManager;
import edu.uci.ics.asterix.metadata.MetadataNode;
import edu.uci.ics.asterix.metadata.api.IAsterixStateProxy;
import edu.uci.ics.asterix.metadata.api.IMetadataNode;
import edu.uci.ics.asterix.metadata.bootstrap.MetadataBootstrap;
import edu.uci.ics.hyracks.api.application.INCApplicationContext;
import edu.uci.ics.hyracks.api.application.INCBootstrap;

public class NCBootstrapImpl implements INCBootstrap {
    private static final Logger LOGGER = Logger.getLogger(NCBootstrapImpl.class.getName());

    private INCApplicationContext ncApplicationContext = null;
    private AsterixAppRuntimeContext runtimeContext;
    private String nodeId;
    private boolean isMetadataNode = false;
    private ThreadedServer apiNodeDataServer;

    @Override
    public void start() throws Exception {
        nodeId = ncApplicationContext.getNodeId();
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Starting Asterix node controller: " + nodeId);
        }

        runtimeContext = new AsterixAppRuntimeContext(ncApplicationContext);
        runtimeContext.initialize();

        ncApplicationContext.setApplicationObject(runtimeContext);

        // Initialize metadata if this node is the metadata node
        IAsterixStateProxy proxy = (IAsterixStateProxy) ncApplicationContext.getDistributedState();
        isMetadataNode = nodeId.equals(proxy.getAsterixProperties().getMetadataNodeName());
        if (isMetadataNode) {
            registerRemoteMetadataNode(proxy);

            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Bootstrapping metadata");
            }

            MetadataManager.INSTANCE = new MetadataManager(proxy);
            MetadataManager.INSTANCE.init();
            MetadataBootstrap.startUniverse(proxy.getAsterixProperties(), ncApplicationContext);

        }

        // Start a sub-component for the API server. This server is only connected to by the 
        // API server that lives on the CC and never by a client wishing to execute AQL.
        // TODO: The API sub-system will change dramatically in the future and this code will go away, 
        // but leave it for now.
        AsterixNodeState ns = (AsterixNodeState) proxy.getAsterixNodeState(nodeId);
        apiNodeDataServer = new ThreadedServer(ns.getAPINodeDataServerPort(), new NodeDataClientThreadFactory());
        apiNodeDataServer.start();
    }

    public void registerRemoteMetadataNode(IAsterixStateProxy proxy) throws RemoteException {
        IMetadataNode stub = null;
        MetadataNode.INSTANCE.initialize(runtimeContext);
        stub = (IMetadataNode) UnicastRemoteObject.exportObject(MetadataNode.INSTANCE, 0);
        proxy.setMetadataNode(stub);

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Metadata node bound");
        }
    }

    @Override
    public void stop() throws Exception {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Stopping Asterix node controller: " + nodeId);
        }

        // Quiesce metadata
        if (isMetadataNode) {
            MetadataBootstrap.stopUniverse();
        }

        apiNodeDataServer.shutdown();
        runtimeContext.deinitialize();
    }

    @Override
    public void setApplicationContext(INCApplicationContext appCtx) {
        this.ncApplicationContext = appCtx;
    }
}