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
package org.apache.openejb.core.stateful;

import java.lang.reflect.Method;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.dgc.VMID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.ejb.EJBAccessException;
import javax.ejb.EJBException;
import javax.ejb.EJBHome;
import javax.ejb.EJBLocalHome;
import javax.ejb.RemoveException;
import javax.ejb.SessionBean;
import javax.ejb.SessionContext;
import javax.ejb.SessionSynchronization;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.apache.openejb.ApplicationException;
import org.apache.openejb.ContainerType;
import org.apache.openejb.DeploymentInfo;
import org.apache.openejb.InjectionProcessor;
import org.apache.openejb.InterfaceType;
import org.apache.openejb.InvalidateReferenceException;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.ProxyInfo;
import org.apache.openejb.RpcContainer;
import org.apache.openejb.SystemException;
import org.apache.openejb.core.CoreDeploymentInfo;
import org.apache.openejb.core.ExceptionType;
import static org.apache.openejb.core.ExceptionType.APPLICATION_ROLLBACK;
import static org.apache.openejb.core.ExceptionType.SYSTEM;
import org.apache.openejb.core.Operation;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.core.interceptor.InterceptorData;
import org.apache.openejb.core.interceptor.InterceptorStack;
import org.apache.openejb.core.stateful.Cache.CacheFilter;
import org.apache.openejb.core.stateful.Cache.CacheListener;
import org.apache.openejb.core.transaction.BeanTransactionPolicy;
import org.apache.openejb.core.transaction.BeanTransactionPolicy.SuspendedTransaction;
import org.apache.openejb.core.transaction.EjbTransactionUtil;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.createTransactionPolicy;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.handleApplicationException;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.handleSystemException;
import org.apache.openejb.core.transaction.EjbUserTransaction;
import org.apache.openejb.core.transaction.TransactionPolicy;
import org.apache.openejb.core.transaction.TransactionPolicy.TransactionSynchronization;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.persistence.EntityManagerAlreadyRegisteredException;
import org.apache.openejb.persistence.JtaEntityManagerRegistry;
import org.apache.openejb.spi.SecurityService;
import org.apache.openejb.util.Index;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.xbean.recipe.ConstructionException;

public class StatefulContainer implements RpcContainer {
    private static final Logger logger = Logger.getInstance(LogCategory.OPENEJB, "org.apache.openejb.util.resources");

    private final Object containerID;
    private final SecurityService securityService;

    // todo this should be part of the constructor
    protected final JtaEntityManagerRegistry entityManagerRegistry = SystemInstance.get().getComponent(JtaEntityManagerRegistry.class);

    /**
     * Index used for getDeployments() and getDeploymentInfo(deploymentId).
     */
    protected final Map<Object, DeploymentInfo> deploymentsById = new HashMap<Object, DeploymentInfo>();

    protected final Cache<Object, Instance> cache;
    private final ConcurrentHashMap<Object, Instance> checkedOutInstances = new ConcurrentHashMap<Object, Instance>();

    public StatefulContainer(Object id, SecurityService securityService, Cache<Object, Instance> cache) {
        this.containerID = id;
        this.securityService = securityService;
        this.cache = cache;
        cache.setListener(new StatefulCacheListener());
    }

    private Map<Method, MethodType> getLifecycleMethodsOfInterface(CoreDeploymentInfo deploymentInfo) {
        Map<Method, MethodType> methods = new HashMap<Method, MethodType>();

        List<Method> removeMethods = deploymentInfo.getRemoveMethods();
        for (Method removeMethod : removeMethods) {
            methods.put(removeMethod, MethodType.REMOVE);

            for (Class businessLocal : deploymentInfo.getBusinessLocalInterfaces()) {
                try {
                    Method method = businessLocal.getMethod(removeMethod.getName());
                    methods.put(method, MethodType.REMOVE);
                } catch (NoSuchMethodException thatsFine) {
                }
            }

            for (Class businessRemote : deploymentInfo.getBusinessRemoteInterfaces()) {
                try {
                    Method method = businessRemote.getMethod(removeMethod.getName());
                    methods.put(method, MethodType.REMOVE);
                } catch (NoSuchMethodException thatsFine) {
                }
            }
        }

        Class legacyRemote = deploymentInfo.getRemoteInterface();
        if (legacyRemote != null) {
            try {
                Method method = legacyRemote.getMethod("remove");
                methods.put(method, MethodType.REMOVE);
            } catch (NoSuchMethodException thatsFine) {
            }
        }

        Class legacyLocal = deploymentInfo.getLocalInterface();
        if (legacyLocal != null) {
            try {
                Method method = legacyLocal.getMethod("remove");
                methods.put(method, MethodType.REMOVE);
            } catch (NoSuchMethodException thatsFine) {
            }
        }

        Class businessLocalHomeInterface = deploymentInfo.getBusinessLocalInterface();
        if (businessLocalHomeInterface != null) {
            for (Method method : DeploymentInfo.BusinessLocalHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        Class businessRemoteHomeInterface = deploymentInfo.getBusinessRemoteInterface();
        if (businessRemoteHomeInterface != null) {
            for (Method method : DeploymentInfo.BusinessRemoteHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        Class homeInterface = deploymentInfo.getHomeInterface();
        if (homeInterface != null) {
            for (Method method : homeInterface.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        Class localHomeInterface = deploymentInfo.getLocalHomeInterface();
        if (localHomeInterface != null) {
            for (Method method : localHomeInterface.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }
        return methods;
    }

    public static enum MethodType {
        CREATE, REMOVE, BUSINESS
    }

    public ContainerType getContainerType() {
        return ContainerType.STATEFUL;
    }

    public Object getContainerID() {
        return containerID;
    }

    public synchronized DeploymentInfo[] deployments() {
        return deploymentsById.values().toArray(new DeploymentInfo[deploymentsById.size()]);
    }

    public synchronized DeploymentInfo getDeploymentInfo(Object deploymentID) {
        return deploymentsById.get(deploymentID);
    }

    public void deploy(DeploymentInfo deploymentInfo) throws OpenEJBException {
        deploy((CoreDeploymentInfo) deploymentInfo);
    }

    public void undeploy(DeploymentInfo info) throws OpenEJBException {
        undeploy((CoreDeploymentInfo) info);
    }

    private synchronized void undeploy(final CoreDeploymentInfo deploymentInfo) throws OpenEJBException {
        deploymentsById.remove(deploymentInfo.getDeploymentID());
        deploymentInfo.setContainer(null);
        deploymentInfo.setContainerData(null);

        cache.removeAll(new CacheFilter<Instance>() {
            public boolean matches(Instance instance) {
                return deploymentInfo == instance.deploymentInfo;
            }
        });

        StatefulContainerData data = (StatefulContainerData) deploymentInfo.getContainerData();
        if (data != null) {
            deploymentInfo.setContainerData(null);
        }
    }

    private synchronized void deploy(CoreDeploymentInfo deploymentInfo) throws OpenEJBException {
        Map<Method, MethodType> methods = getLifecycleMethodsOfInterface(deploymentInfo);

        deploymentsById.put(deploymentInfo.getDeploymentID(), deploymentInfo);
        deploymentInfo.setContainer(this);
        deploymentInfo.setContainerData(new StatefulContainerData(new Index<Method, MethodType>(methods)));
    }

    /**
     * @deprecated use invoke signature without 'securityIdentity' argument.
     */
    public Object invoke(Object deployID, Method callMethod, Object[] args, Object primKey, Object securityIdentity) throws OpenEJBException {
        return invoke(deployID, callMethod.getDeclaringClass(), callMethod, args, primKey);
    }

    public Object invoke(Object deployID, Class callInterface, Method callMethod, Object [] args, Object primKey) throws OpenEJBException {
        CoreDeploymentInfo deployInfo = (CoreDeploymentInfo) this.getDeploymentInfo(deployID);
        if (deployInfo == null) {
            throw new OpenEJBException("Deployment does not exist in this container. Deployment(id='" + deployID + "'), Container(id='" + containerID + "')");
        }

        StatefulContainerData data = (StatefulContainerData) deployInfo.getContainerData();
        MethodType methodType = data.getMethodIndex().get(callMethod);
        methodType = (methodType != null) ? methodType : MethodType.BUSINESS;

        switch (methodType) {
            case CREATE:
                return createEJBObject(deployInfo, callInterface, callMethod, args);
            case REMOVE:
                return removeEJBObject(deployInfo, primKey, callInterface, callMethod, args);
            default:
                return businessMethod(deployInfo, primKey, callInterface, callMethod, args);
        }
    }

    protected ProxyInfo createEJBObject(CoreDeploymentInfo deploymentInfo, Class callInterface, Method callMethod, Object [] args) throws OpenEJBException {
        // generate a new primary key
        Object primaryKey = newPrimaryKey();


        ThreadContext createContext = new ThreadContext(deploymentInfo, primaryKey);
        ThreadContext oldCallContext = ThreadContext.enter(createContext);
        try {
            // Security check
            checkAuthorization(deploymentInfo, callMethod, callInterface);

            // Create the extended entity managers for this instance
            Index<EntityManagerFactory, EntityManager> entityManagers = createEntityManagers(deploymentInfo);

            // Register the newly created entity managers
            if (entityManagers != null) {
                try {
                    entityManagerRegistry.addEntityManagers((String) deploymentInfo.getDeploymentID(), primaryKey, entityManagers);
                } catch (EntityManagerAlreadyRegisteredException e) {
                    throw new EJBException(e);
                }
            }

            // Start transaction
            TransactionPolicy txPolicy = createTransactionPolicy(createContext.getDeploymentInfo().getTransactionType(callMethod), createContext);

            Instance instance = null;
            try {
                // Create new instance
                instance = newInstance(primaryKey, deploymentInfo.getBeanClass(), entityManagers);

                // Register for synchronization callbacks
                registerSessionSynchronization(instance, createContext);
              
                // Invoke create for legacy beans
                if (!callMethod.getDeclaringClass().equals(DeploymentInfo.BusinessLocalHome.class) &&
                        !callMethod.getDeclaringClass().equals(DeploymentInfo.BusinessRemoteHome.class)){

                    // Setup for business invocation
                    createContext.setCurrentOperation(Operation.CREATE);
                    createContext.setCurrentAllowedStates(StatefulContext.getStates());
                    Method createOrInit = deploymentInfo.getMatchingBeanMethod(callMethod);
                    createContext.set(Method.class, createOrInit);

                    // Initialize interceptor stack
                    InterceptorStack interceptorStack = new InterceptorStack(instance.bean, createOrInit, Operation.CREATE, new ArrayList<InterceptorData>(), new HashMap<String, Object>());

                    // Invoke
                    if (args == null){
                        interceptorStack.invoke();
                    } else {
                        interceptorStack.invoke(args);
                    }
                }
            } catch (Throwable e) {
                handleException(createContext, txPolicy, e);
            } finally {
                afterInvoke(createContext, txPolicy, instance);
            }

            return new ProxyInfo(deploymentInfo, primaryKey);
        } finally {
            ThreadContext.exit(oldCallContext);
        }
    }

    protected Object newPrimaryKey() {
        return new VMID();
    }

    protected Object removeEJBObject(CoreDeploymentInfo deploymentInfo, Object primKey, Class callInterface, Method callMethod, Object[] args) throws OpenEJBException {
        if (primKey == null) throw new NullPointerException("primKey is null");

        ThreadContext callContext = new ThreadContext(deploymentInfo, primKey);
        ThreadContext oldCallContext = ThreadContext.enter(callContext);
        try {
            // Security check
            checkAuthorization(deploymentInfo, callMethod, callInterface);

            // If a bean managed transaction is active, the bean can not be removed
            InterfaceType interfaceType = deploymentInfo.getInterfaceType(callInterface);
            if (interfaceType.isComponent()) {
                Instance instance = checkedOutInstances.get(primKey);
                if (instance != null && instance.getBeanTransaction() != null) {
                    throw new ApplicationException(new RemoveException("A stateful EJB enrolled in a bean-managed transaction can not be removed"));
                }
            }

            // Start transaction
            TransactionPolicy txPolicy = createTransactionPolicy(callContext.getDeploymentInfo().getTransactionType(callMethod), callContext);

            Object returnValue = null;
            boolean retain = false;
            Instance instance = null;
            Method runMethod = null;
            try {
                // Obtain instance
                instance = obtainInstance(primKey, callContext);
                if (instance == null) throw new ApplicationException(new javax.ejb.NoSuchEJBException());

                // Resume previous Bean transaction if there was one
                if (txPolicy instanceof BeanTransactionPolicy){
                    // Resume previous Bean transaction if there was one
                    SuspendedTransaction suspendedTransaction = instance.getBeanTransaction();
                    if (suspendedTransaction != null) {
                        instance.setBeanTransaction(null);
                        BeanTransactionPolicy beanTxEnv = (BeanTransactionPolicy) txPolicy;
                        beanTxEnv.resumeUserTransaction(suspendedTransaction);
                    }
                }

                // Register the entity managers
                registerEntityManagers(instance, callContext);

                // Register for synchronization callbacks
                registerSessionSynchronization(instance, callContext);

                // Setup for remove invocation
                callContext.setCurrentOperation(Operation.REMOVE);
                callContext.setCurrentAllowedStates(StatefulContext.getStates());
                callContext.setInvokedInterface(callInterface);
                runMethod = deploymentInfo.getMatchingBeanMethod(callMethod);
                callContext.set(Method.class, runMethod);

                // Do not pass arguments on home.remove(remote) calls
                Class<?> declaringClass = callMethod.getDeclaringClass();
                if (declaringClass.equals(EJBHome.class) || declaringClass.equals(EJBLocalHome.class)){
                    args = new Object[]{};
                }
                
                // Initialize interceptor stack
                List<InterceptorData> interceptors = deploymentInfo.getMethodInterceptors(runMethod);
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, runMethod, Operation.REMOVE, interceptors, instance.interceptors);

                // Invoke
                if (args == null){
                    returnValue = interceptorStack.invoke();
                } else {
                    returnValue = interceptorStack.invoke(args);
                }
            } catch (Throwable e) {
                if (interfaceType.isBusiness() && deploymentInfo.getExceptionType(e) == SYSTEM) {
                    retain = deploymentInfo.retainIfExeption(runMethod);
                }
                handleException(callContext, txPolicy, e);
            } finally {
                if (!retain) {
                    try {
                        callContext.setCurrentOperation(Operation.PRE_DESTROY);
                        List<InterceptorData> callbackInterceptors = deploymentInfo.getCallbackInterceptors();
                        InterceptorStack interceptorStack = new InterceptorStack(instance.bean, null, Operation.PRE_DESTROY, callbackInterceptors, instance.interceptors);
                        interceptorStack.invoke();
                    } catch (Throwable callbackException) {
                        String logMessage = "An unexpected exception occured while invoking the preDestroy method on the removed Stateful SessionBean instance; " + callbackException.getClass().getName() + " " + callbackException.getMessage();

                        /* [1] Log the exception or error */
                        logger.error(logMessage);

                    } finally {
                        callContext.setCurrentOperation(Operation.REMOVE);
                    }

                    // todo destroy extended persistence contexts
                    discardInstance(callContext);
                }

                // Commit transaction
                afterInvoke(callContext, txPolicy, instance);
            }

            return returnValue;
        } finally {
            ThreadContext.exit(oldCallContext);
        }
    }

    protected Object businessMethod(CoreDeploymentInfo deploymentInfo, Object primKey, Class callInterface, Method callMethod, Object[] args) throws OpenEJBException {
        ThreadContext callContext = new ThreadContext(deploymentInfo, primKey);
        ThreadContext oldCallContext = ThreadContext.enter(callContext);
        try {
            // Security check
            checkAuthorization(deploymentInfo, callMethod, callInterface);

            // Start transaction
            TransactionPolicy txPolicy = createTransactionPolicy(callContext.getDeploymentInfo().getTransactionType(callMethod), callContext);

            Object returnValue = null;
            Instance instance = null;
            try {
                // Obtain instance
                instance = obtainInstance(primKey, callContext);

                // Resume previous Bean transaction if there was one
                if (txPolicy instanceof BeanTransactionPolicy){
                    SuspendedTransaction suspendedTransaction = instance.getBeanTransaction();
                    if (suspendedTransaction != null) {
                        instance.setBeanTransaction(null);
                        BeanTransactionPolicy beanTxEnv = (BeanTransactionPolicy) txPolicy;
                        beanTxEnv.resumeUserTransaction(suspendedTransaction);
                    }
                }

                // Register the entity managers
                registerEntityManagers(instance, callContext);
                // Register for synchronization callbacks
                registerSessionSynchronization(instance, callContext);

                // Setup for business invocation
                callContext.setCurrentOperation(Operation.BUSINESS);
                callContext.setCurrentAllowedStates(StatefulContext.getStates());
                callContext.setInvokedInterface(callInterface);
                Method runMethod = deploymentInfo.getMatchingBeanMethod(callMethod);
                callContext.set(Method.class, runMethod);

                // Initialize interceptor stack
                List<InterceptorData> interceptors = deploymentInfo.getMethodInterceptors(runMethod);
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, runMethod, Operation.BUSINESS, interceptors, instance.interceptors);

                // Invoke
                returnValue = interceptorStack.invoke(args);
            } catch (Throwable e) {
                handleException(callContext, txPolicy, e);
            } finally {
                // Commit transaction
                afterInvoke(callContext, txPolicy, instance);
            }
            return returnValue;
        } finally {
            ThreadContext.exit(oldCallContext);
        }
    }

    private Instance newInstance(Object primaryKey, Class beanClass, Map<EntityManagerFactory, EntityManager> entityManagers) throws OpenEJBException {
        Instance instance = null;

        ThreadContext threadContext = ThreadContext.getThreadContext();
        Operation currentOperation = threadContext.getCurrentOperation();
        try {
            ThreadContext callContext = ThreadContext.getThreadContext();
            CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();
            Context ctx = deploymentInfo.getJndiEnc();

            // Get or create the session context
            SessionContext sessionContext;
            synchronized (this) {
                try {
                    sessionContext = (SessionContext) ctx.lookup("java:comp/EJBContext");
                } catch (NamingException e1) {
                    StatefulUserTransaction userTransaction = new StatefulUserTransaction(new EjbUserTransaction(), entityManagerRegistry);
                    sessionContext = new StatefulContext(securityService, userTransaction);
                    ctx.bind("java:comp/EJBContext", sessionContext);
                }
            }

            // Create bean instance
            InjectionProcessor injectionProcessor = new InjectionProcessor(beanClass, deploymentInfo.getInjections(), null, null, ctx);
            try {
                if (SessionBean.class.isAssignableFrom(beanClass) || beanClass.getMethod("setSessionContext", SessionContext.class) != null) {
                    callContext.setCurrentOperation(Operation.INJECTION);
                    injectionProcessor.setProperty("sessionContext", sessionContext);
                }
            } catch (NoSuchMethodException ignored) {
                // bean doesn't have a setSessionContext method, so we don't need to inject one
            }
            Object bean = injectionProcessor.createInstance();

            // Create interceptors
            HashMap<String, Object> interceptorInstances = new HashMap<String, Object>();
            for (InterceptorData interceptorData : deploymentInfo.getAllInterceptors()) {
                if (interceptorData.getInterceptorClass().equals(beanClass)) {
                    continue;
                }

                Class clazz = interceptorData.getInterceptorClass();
                InjectionProcessor interceptorInjector = new InjectionProcessor(clazz, deploymentInfo.getInjections(), ctx);
                try {
                    Object interceptorInstance = interceptorInjector.createInstance();
                    interceptorInstances.put(clazz.getName(), interceptorInstance);
                } catch (ConstructionException e) {
                    throw new Exception("Failed to create interceptor: " + clazz.getName(), e);
                }
            }
            interceptorInstances.put(beanClass.getName(), bean);

            // Invoke post construct method
            callContext.setCurrentOperation(Operation.POST_CONSTRUCT);
            List<InterceptorData> callbackInterceptors = deploymentInfo.getCallbackInterceptors();
            InterceptorStack interceptorStack = new InterceptorStack(bean, null, Operation.POST_CONSTRUCT, callbackInterceptors, interceptorInstances);
            interceptorStack.invoke();

            // Wrap-up everthing into a object
            instance = new Instance(deploymentInfo, primaryKey, bean, interceptorInstances, entityManagers);

        } catch (Throwable callbackException) {
            discardInstance(threadContext);
            handleSystemException(threadContext.getTransactionPolicy(), callbackException, threadContext);
        } finally {
            threadContext.setCurrentOperation(currentOperation);
        }

        // add to cache
        cache.add(primaryKey, instance);

        // instance starts checked-out
        checkedOutInstances.put(primaryKey, instance);

        return instance;
    }

    private Instance obtainInstance(Object primaryKey, ThreadContext callContext) throws OpenEJBException {
        if (primaryKey == null) {
            throw new SystemException(new NullPointerException("Cannot obtain an instance of the stateful session bean with a null session id"));
        }

        // Find the instance
        Instance instance = checkedOutInstances.get(primaryKey);
        if (instance == null) {
            try {
                instance = cache.checkOut(primaryKey);
            } catch (OpenEJBException e) {
                throw e;
            } catch (Exception e) {
                throw new SystemException("Unexpected load exception", e);
            }

            // Did we find the instance?
            if (instance == null) {
                throw new InvalidateReferenceException(new NoSuchObjectException("Not Found"));
            }

            // remember instance until it is returned to the cache
            checkedOutInstances.put(primaryKey, instance);
        }


        synchronized (instance) {
            // Is the instance alreayd in use?
            if (instance.isInUse()) {
                // the bean is already being invoked; the only reentrant/concurrent operations allowed are Session synchronization callbacks
                Operation currentOperation = callContext.getCurrentOperation();
                if (currentOperation != Operation.AFTER_COMPLETION && currentOperation != Operation.BEFORE_COMPLETION) {
                    throw new ApplicationException(new RemoteException("Concurrent calls not allowed"));
                }
            }

            // Mark the instance in use so we can detect reentrant calls
            instance.setInUse(true);

            return instance;
        }
    }

    private void releaseInstance(Instance instance) {
        // Don't pool if the bean has been undeployed
        if (instance.deploymentInfo.isDestroyed()) return;

        // verify the instance is not associated with a bean-managed transaction
        if (instance.getBeanTransaction() != null) {
            new IllegalStateException("Instance has an active bean-managed transaction");
        }

        // no longer in use
        instance.setInUse(false);

        // return to cache
        cache.checkIn(instance.primaryKey);

        // no longer checked out
        checkedOutInstances.remove(instance.primaryKey);
    }

    private void discardInstance(ThreadContext threadContext) {
        Object primaryKey = threadContext.getPrimaryKey();
        if (primaryKey == null) {
            return;
        }

        checkedOutInstances.remove(primaryKey);
        cache.remove(primaryKey);
    }

    private void checkAuthorization(CoreDeploymentInfo deployInfo, Method callMethod, Class callInterface) throws ApplicationException {
        boolean authorized = securityService.isCallerAuthorized(callMethod, deployInfo.getInterfaceType(callInterface));
        if (!authorized) {
            throw new ApplicationException(new EJBAccessException("Unauthorized Access by Principal Denied"));
        }
    }

    private void handleException(ThreadContext callContext, TransactionPolicy txPolicy, Throwable e) throws ApplicationException {
        if (e instanceof ApplicationException) {
            throw (ApplicationException) e;
        }

        ExceptionType type = callContext.getDeploymentInfo().getExceptionType(e);
        if (type == SYSTEM) {
            discardInstance(callContext);
            handleSystemException(txPolicy, e, callContext);
        } else {
            handleApplicationException(txPolicy, e, type == APPLICATION_ROLLBACK);
        }
    }

    private void afterInvoke(ThreadContext callContext, TransactionPolicy txPolicy, Instance instance) throws OpenEJBException {
        try {
            unregisterEntityManagers(instance, callContext);
            if (instance != null && txPolicy instanceof BeanTransactionPolicy) {
                // suspend the currently running transaction if any
                SuspendedTransaction suspendedTransaction = null;
                try {
                    BeanTransactionPolicy beanTxEnv = (BeanTransactionPolicy) txPolicy;
                    suspendedTransaction = beanTxEnv.suspendUserTransaction();
                } catch (SystemException e) {
                    handleSystemException(txPolicy, e, callContext);
                } finally {
                    instance.setBeanTransaction(suspendedTransaction);
                }
            }
        } finally {
            if (instance != null) {
                instance.setInUse(false);
            }
            EjbTransactionUtil.afterInvoke(txPolicy, callContext);
        }
    }

    private Index<EntityManagerFactory, EntityManager> createEntityManagers(CoreDeploymentInfo deploymentInfo) {
        // create the extended entity managers
        Index<EntityManagerFactory, Map> factories = deploymentInfo.getExtendedEntityManagerFactories();
        Index<EntityManagerFactory, EntityManager> entityManagers = null;
        if (factories != null && factories.size() > 0) {
            entityManagers = new Index<EntityManagerFactory, EntityManager>(new ArrayList<EntityManagerFactory>(factories.keySet()));
            for (Map.Entry<EntityManagerFactory, Map> entry : factories.entrySet()) {
                EntityManagerFactory entityManagerFactory = entry.getKey();
                Map properties = entry.getValue();


                EntityManager entityManager = entityManagerRegistry.getInheritedEntityManager(entityManagerFactory);
                if (entityManager == null) {
                    if (properties != null) {
                        entityManager = entityManagerFactory.createEntityManager(properties);
                    } else {
                        entityManager = entityManagerFactory.createEntityManager();
                    }
                }
                entityManagers.put(entityManagerFactory, entityManager);
            }
        }
        return entityManagers;
    }

    private void registerEntityManagers(Instance instance, ThreadContext callContext) throws OpenEJBException {
        if (entityManagerRegistry == null) return;

        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();

        // get the factories
        Index<EntityManagerFactory, Map> factories = deploymentInfo.getExtendedEntityManagerFactories();
        if (factories == null) return;

        // get the managers for the factories
        Map<EntityManagerFactory, EntityManager> entityManagers = instance.getEntityManagers(factories);
        if (entityManagers == null) return;

        // register them
        try {
            entityManagerRegistry.addEntityManagers((String) deploymentInfo.getDeploymentID(), instance.primaryKey, entityManagers);
        } catch (EntityManagerAlreadyRegisteredException e) {
            throw new EJBException(e);
        }
    }

    private void unregisterEntityManagers(Instance instance, ThreadContext callContext) {
        if (entityManagerRegistry == null) return;
        if (instance == null) return;

        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();

        // register them
        entityManagerRegistry.removeEntityManagers((String) deploymentInfo.getDeploymentID(), instance.primaryKey);
    }


    private void registerSessionSynchronization(Instance instance, ThreadContext callContext)  {
        TransactionPolicy txPolicy = callContext.getTransactionPolicy();
        if (txPolicy == null) {
            throw new IllegalStateException("ThreadContext does not contain a TransactionEnvironment");
        }

        SessionSynchronizationCoordinator coordinator = (SessionSynchronizationCoordinator) txPolicy.getResource(SessionSynchronizationCoordinator.class);
        if (coordinator == null) {
            coordinator = new SessionSynchronizationCoordinator(txPolicy);
            txPolicy.registerSynchronization(coordinator);
            txPolicy.putResource(SessionSynchronizationCoordinator.class, coordinator);
        }

        // SessionSynchronization are only enabled for beans after CREATE that are not bean-managed and implement the SessionSynchronization interface
        boolean sessionSynchronization = callContext.getCurrentOperation() != Operation.CREATE &&
                callContext.getDeploymentInfo().isBeanManagedTransaction() &&
                instance.bean instanceof SessionSynchronization &&
                txPolicy.isTransactionActive();

        coordinator.registerSessionSynchronization(instance, callContext.getDeploymentInfo(), callContext.getPrimaryKey(), sessionSynchronization);
    }

    /**
     * SessionSynchronizationCoordinator handles afterBegin, beforeCompletion and afterCompletion callbacks.
     *
     * This class also is responsible for calling releaseInstance after the transaction completes. 
     */
    private class SessionSynchronizationCoordinator implements TransactionSynchronization {
        private final Map<Object, Instance> registry = new HashMap<Object, Instance>();
        private final TransactionPolicy txPolicy;

        private SessionSynchronizationCoordinator(TransactionPolicy txPolicy) {
            this.txPolicy = txPolicy;
        }

        private void registerSessionSynchronization(Instance instance, CoreDeploymentInfo deploymentInfo, Object primaryKey, boolean sessionSynchronization) {
            // register
            if (!registry.containsKey(primaryKey)) {
                registry.put(primaryKey, instance);
            }

            // check if afterBegin has already been invoked or if this is not a session synchronization bean
            if (instance.isCallSessionSynchronization() || !sessionSynchronization) {
                return;
            }
            instance.setCallSessionSynchronization();

            // Invoke afterBegin
            ThreadContext callContext = new ThreadContext(instance.deploymentInfo, instance.primaryKey, Operation.AFTER_BEGIN);
            ThreadContext oldCallContext = ThreadContext.enter(callContext);
            try {

                Method afterBegin = SessionSynchronization.class.getMethod("afterBegin");

                List<InterceptorData> interceptors = deploymentInfo.getMethodInterceptors(afterBegin);
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, afterBegin, Operation.AFTER_BEGIN, interceptors, instance.interceptors);
                interceptorStack.invoke();

            } catch (Exception e) {
                String message = "An unexpected system exception occured while invoking the afterBegin method on the SessionSynchronization object";

                // [1] Log the exception or error
                logger.error(message, e);

                // Caller handles transaction rollback and discardInstance

                // [4] throw the java.rmi.RemoteException to the client
                throw new RuntimeException(message, e);
            } finally {
                ThreadContext.exit(oldCallContext);
            }
        }

        public void beforeCompletion() {
            for (Instance instance : registry.values()) {
                // don't call beforeCompletion when transaction is marked rollback only
                if (txPolicy.isRollbackOnly()) return;

                // only call beforeCompletion on beans with session synchronization
                if (!instance.isCallSessionSynchronization()) continue;

                // Invoke beforeCompletion
                ThreadContext callContext = new ThreadContext(instance.deploymentInfo, instance.primaryKey, Operation.BEFORE_COMPLETION);
                callContext.setCurrentAllowedStates(StatefulContext.getStates());
                ThreadContext oldCallContext = ThreadContext.enter(callContext);
                try {
                    instance.setInUse(true);

                    Method beforeCompletion = SessionSynchronization.class.getMethod("beforeCompletion");

                    List<InterceptorData> interceptors = instance.deploymentInfo.getMethodInterceptors(beforeCompletion);
                    InterceptorStack interceptorStack = new InterceptorStack(instance.bean, beforeCompletion, Operation.BEFORE_COMPLETION, interceptors, instance.interceptors);
                    interceptorStack.invoke();

                    instance.setInUse(false);
                } catch (InvalidateReferenceException e) {
                    // exception has alredy been handled
                } catch (Exception e) {
                    String message = "An unexpected system exception occured while invoking the beforeCompletion method on the SessionSynchronization object";

                    // [1] Log the exception or error
                    logger.error(message, e);

                    // [2] Mark the transaction for rollback.
                    txPolicy.setRollbackOnly();

                    // [3] Discard the instance
                    discardInstance(callContext);

                    // [4] throw the java.rmi.RemoteException to the client
                    throw new RuntimeException(message, e);
                } finally {
                    ThreadContext.exit(oldCallContext);
                }
            }
        }

        public void afterCompletion(Status status) {
            Throwable firstException = null;
            for (Instance instance : registry.values()) {

                ThreadContext callContext = new ThreadContext(instance.deploymentInfo, instance.primaryKey, Operation.AFTER_COMPLETION);
                callContext.setCurrentAllowedStates(StatefulContext.getStates());
                ThreadContext oldCallContext = ThreadContext.enter(callContext);
                try {
                    instance.setInUse(true);
                    if (instance.isCallSessionSynchronization()) {
                        Method afterCompletion = SessionSynchronization.class.getMethod("afterCompletion", boolean.class);

                        List<InterceptorData> interceptors = instance.deploymentInfo.getMethodInterceptors(afterCompletion);
                        InterceptorStack interceptorStack = new InterceptorStack(instance.bean, afterCompletion, Operation.AFTER_COMPLETION, interceptors, instance.interceptors);
                        interceptorStack.invoke(status == Status.COMMITTED);
                    }

                    releaseInstance(instance);
                } catch (InvalidateReferenceException inv) {
                    // exception has alredy been handled
                } catch (Throwable e) {
                    String message = "An unexpected system exception occured while invoking the afterCompletion method on the SessionSynchronization object";

                    // [1] Log the exception or error
                    logger.error(message, e);

                    // Transaction is complete so can not be rolled back

                    // [3] Discard the instance
                    discardInstance(callContext);

                    // [4] throw throw first exception to the client
                    if (firstException == null) firstException = e;
                } finally {
                    ThreadContext.exit(oldCallContext);
                }
            }

            if (firstException != null) {
                throw new RuntimeException("An unexpected system exception occured while invoking the afterCompletion method on the SessionSynchronization object", firstException);
            }
        }
    }

    public class StatefulCacheListener implements CacheListener<Instance> {
        public void afterLoad(Instance instance) throws SystemException, ApplicationException {
            CoreDeploymentInfo deploymentInfo = instance.deploymentInfo;

            ThreadContext threadContext = new ThreadContext(instance.deploymentInfo, instance.primaryKey, Operation.ACTIVATE);
            ThreadContext oldContext = ThreadContext.enter(threadContext);
            try {
                Method remove = instance.bean instanceof SessionBean ? SessionBean.class.getMethod("ejbActivate") : null;

                List<InterceptorData> callbackInterceptors = deploymentInfo.getCallbackInterceptors();
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, remove, Operation.ACTIVATE, callbackInterceptors, instance.interceptors);

                interceptorStack.invoke();
            } catch (Throwable callbackException) {
                discardInstance(threadContext);
                handleSystemException(threadContext.getTransactionPolicy(), callbackException, threadContext);
            } finally {
                ThreadContext.exit(oldContext);
            }
        }

        public void beforeStore(Instance instance) {
            CoreDeploymentInfo deploymentInfo = instance.deploymentInfo;

            ThreadContext threadContext = new ThreadContext(deploymentInfo, instance.primaryKey, Operation.PASSIVATE);
            ThreadContext oldContext = ThreadContext.enter(threadContext);
            try {
                Method passivate = instance.bean instanceof SessionBean ? SessionBean.class.getMethod("ejbPassivate") : null;

                List<InterceptorData> callbackInterceptors = deploymentInfo.getCallbackInterceptors();
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, passivate, Operation.PASSIVATE, callbackInterceptors, instance.interceptors);

                interceptorStack.invoke();

            } catch (Throwable e) {
                logger.error("An unexpected exception occured while invoking the ejbPassivate method on the Stateful SessionBean instance", e);
            } finally {
                ThreadContext.exit(oldContext);
            }
        }

        public void timedOut(Instance instance) {
            CoreDeploymentInfo deploymentInfo = instance.deploymentInfo;

            ThreadContext threadContext = new ThreadContext(deploymentInfo, instance.primaryKey, Operation.PRE_DESTROY);
            threadContext.setCurrentAllowedStates(StatefulContext.getStates());
            ThreadContext oldContext = ThreadContext.enter(threadContext);
            try {
                Method remove = instance.bean instanceof SessionBean ? SessionBean.class.getMethod("ejbRemove") : null;

                List<InterceptorData> callbackInterceptors = deploymentInfo.getCallbackInterceptors();
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, remove, Operation.PRE_DESTROY, callbackInterceptors, instance.interceptors);

                interceptorStack.invoke();
            } catch (Throwable e) {
                logger.error("An unexpected exception occured while invoking the ejbRemove method on the timed-out Stateful SessionBean instance", e);
            } finally {
                logger.info("Removing the timed-out stateful session bean instance " + instance.primaryKey);
                ThreadContext.exit(oldContext);
            }
        }
    }    

    private static class StatefulContainerData {
        private final Index<Method, MethodType> methodIndex;

        private StatefulContainerData(Index<Method, MethodType> methodIndex) {
            this.methodIndex = methodIndex;
        }

        public Index<Method, MethodType> getMethodIndex() {
            return methodIndex;
        }

    }
}
