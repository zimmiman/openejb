/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.camel;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultEndpoint;

import java.util.Map;

public class OpenEJBEndpoint extends DefaultEndpoint {
    private String uri;
    private String command;
    private Map<String, Object> parameters;

    public OpenEJBEndpoint(final String uri, final String command, final Map<String, Object> parameters) {
        this.uri = uri;
        this.command = command;
        this.parameters = parameters;
    }

    @Override
    public Producer createProducer() throws Exception {
        return createOpenEJBProducer(command, this, parameters);
    }

    public Producer createOpenEJBProducer(final String command, final Endpoint endpoint, final Map<String, Object> parameters) {
        if ("deploy".equals(command)) {
            return new DeployProducer(this, parameters);
        } else if ("undeploy".equals(command)) {
            return new UnDeployProducer(this, parameters);
        } else if ("auto".equals(command)) {
            return new AutoProducer(this, parameters);
        }
        throw new IllegalArgumentException("command '" + command + "' is not supported");        
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    protected String createEndpointUri() {
        return uri;
    }
}
