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
package org.apache.openejb.core.mdb;

import javax.naming.NameNotFoundException;

import org.apache.openejb.core.ivm.naming.EncReference;
import org.apache.openejb.core.ivm.naming.Reference;
import org.apache.openejb.core.Operation;
import org.apache.openejb.core.BaseContext;


public class MdbEncReference extends EncReference {

    public MdbEncReference(Reference ref) {
        super(ref);
    }

    public void checkOperation(BaseContext context) throws NameNotFoundException {
        if (!context.isEnterpriseBeanAccessAllowed())
            throw new NameNotFoundException("Operation Not Allowed");
    }
}
