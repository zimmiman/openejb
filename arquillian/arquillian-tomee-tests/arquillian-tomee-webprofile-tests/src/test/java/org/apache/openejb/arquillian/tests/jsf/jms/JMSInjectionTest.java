/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openejb.arquillian.tests.jsf.jms;

import org.apache.openejb.arquillian.tests.jsf.JSFs;
import org.apache.openejb.loader.IO;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.asset.ClassLoaderAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URL;
import java.util.logging.Logger;

import static junit.framework.Assert.assertTrue;

@RunWith(Arquillian.class)
public class JMSInjectionTest extends JSFs {

    @ArquillianResource
    private URL url;

    static Logger logger = Logger.getLogger(JMSInjectionTest.class.getName());

    @Deployment
    public static WebArchive getArchive() {

        return base("jsf-jms-test.war")
                .addPackage(JMSInjectionTest.class.getPackage())
                .addAsWebResource(new ClassLoaderAsset(
                        JMSInjectionTest.class.getPackage().getName().replace('.', '/').concat("/").concat("dummy.xhtml")), "dummy.xhtml");
    }

    @Test
    public void testJMSInjection() throws Exception {
        validateTest("queueInjected");
        validateTest("topicInjected");
        validateTest("connectionFactoryInjected");
    }

    private void validateTest(final String expectedOutput) throws IOException {
        final String output = IO.slurp(new URL(url.toExternalForm() + "dummy.xhtml"));
        assertTrue("Output should contain: " + expectedOutput + "; and not " + output, output.contains(expectedOutput));
    }


}