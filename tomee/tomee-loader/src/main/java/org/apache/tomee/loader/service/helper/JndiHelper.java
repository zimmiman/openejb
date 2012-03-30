/*
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

package org.apache.tomee.loader.service.helper;

import javax.naming.Context;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public interface JndiHelper {

    Map<String, Object> getJndi();

    List<Method> getJndiMethods(String path);

    Context getContext(String user, String password);

    Object invokeJndiMethod(Context context, String path, String methodName, Object... params);

}
