/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.brooklyn.camp.brooklyn.dsl;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.BrooklynDslDeferredSupplier;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.methods.BrooklynDslCommon;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.ValueResolver;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.time.Duration;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

public class DslTest extends BrooklynAppUnitTestSupport {

    private static final int MAX_PARALLEL_RESOLVERS = 50;
    private static final int MANY_RESOLVER_ITERATIONS = 100;
    
    private ListeningScheduledExecutorService executor;
    private Random random = new Random();
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor());
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            if (executor != null) executor.shutdownNow();
        } finally {
            super.tearDown();
        }
    }
    
    @Test
    public void testAttributeWhenReadyEmptyDoesNotBlock() throws Exception {
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.attributeWhenReady(TestApplication.MY_ATTRIBUTE.getName());
        Maybe<?> actualValue = execDslRealRealQuick(dsl, TestApplication.MY_ATTRIBUTE.getType(), app);
        assertTrue(actualValue.isAbsent());
    }

    @Test
    public void testAttributeWhenReadyEmptyImmediatelyDoesNotBlock() throws Exception {
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.attributeWhenReady(TestApplication.MY_ATTRIBUTE.getName());
        Maybe<?> actualValue = execDslImmediately(dsl, TestApplication.MY_ATTRIBUTE.getType(), app, true);
        assertTrue(actualValue.isAbsent());
    }

    @Test
    public void testAttributeWhenReady() throws Exception {
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.attributeWhenReady(TestEntity.NAME.getName());
        new AttributeWhenReadyTestWorker(app, TestEntity.NAME, dsl).run();
    }

    @Test
    public void testAttributeWhenReadyBlocksUntilReady() throws Exception {
        // Fewer iterations, because there is a sleep each time
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.attributeWhenReady(TestEntity.NAME.getName());
        new AttributeWhenReadyTestWorker(app, TestEntity.NAME, dsl).satisfiedAsynchronously(true).resolverIterations(2).run();
    }

    @Test(groups="Integration")
    public void testAttributeWhenReadyConcurrent() throws Exception {
        final BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.attributeWhenReady(TestEntity.NAME.getName());
        runConcurrentWorker(new Supplier<Runnable>() {
            @Override
            public Runnable get() {
                return new AttributeWhenReadyTestWorker(app, TestEntity.NAME, dsl);
            }
        });
    }

    @Test
    public void testConfig() throws Exception {
        ConfigKey<String> configKey = ConfigKeys.newStringConfigKey("testConfig");
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.config(configKey.getName());
        new ConfigTestWorker(app, configKey, dsl).run();
    }

    @Test
    public void testConfigWithDsl() throws Exception {
        ConfigKey<?> configKey = ConfigKeys.newConfigKey(Entity.class, "testConfig");
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.config(configKey.getName());
        Supplier<ConfigValuePair> valueSupplier = new Supplier<ConfigValuePair>() {
            @Override public ConfigValuePair get() {
                return new ConfigValuePair(BrooklynDslCommon.root(), app);
            }
        };
        new ConfigTestWorker(app, configKey, valueSupplier, dsl).run();
    }

    @Test
    public void testConfigWithDslNotReadyImmediately() throws Exception {
        final ConfigKey<String> configKey = ConfigKeys.newStringConfigKey("testConfig");
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.config(configKey.getName());
        Function<Entity, ConfigValuePair> valueSupplier = new Function<Entity, ConfigValuePair>() {
            private ListenableScheduledFuture<?> future;
            @Override
            public ConfigValuePair apply(final Entity entity) {
                try {
                    // If executed in a loop, then wait for previous call's future to complete.
                    // If previous assertion used getImmediately, then it won't have waited for the future to complete.
                    if (future != null) {
                        future.get(Asserts.DEFAULT_LONG_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS);
                        future = null;
                    }
    
                    // Reset sensor - otherwise if run in a loop the old value will be picked up, before our execute sets the new value
                    entity.sensors().set(TestApplication.MY_ATTRIBUTE, null);
                    
                    final String expectedValue = Identifiers.makeRandomId(10);
                    Runnable job = new Runnable() {
                        public void run() {
                            entity.sensors().set(TestApplication.MY_ATTRIBUTE, expectedValue);
                        }
                    };
                    future = executor.schedule(job, random.nextInt(20), TimeUnit.MILLISECONDS);
    
                    BrooklynDslDeferredSupplier<?> attributeDsl = BrooklynDslCommon.attributeWhenReady(TestApplication.MY_ATTRIBUTE.getName());
                    return new ConfigValuePair(attributeDsl, expectedValue);

                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                }
            }
        };
        new ConfigTestWorker(app, configKey, valueSupplier, dsl).satisfiedAsynchronously(true).resolverIterations(2).run();
    }
    
    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testConfigImmediatelyDoesNotBlock() throws Exception {
        ConfigKey<String> configKey = ConfigKeys.newStringConfigKey("testConfig");
        BrooklynDslDeferredSupplier<?> attributeDsl = BrooklynDslCommon.attributeWhenReady(TestApplication.MY_ATTRIBUTE.getName());
        app.config().set((ConfigKey)configKey, attributeDsl); // ugly cast because val is DSL, resolving to a string
        BrooklynDslDeferredSupplier<?> configDsl = BrooklynDslCommon.config(configKey.getName());
        Maybe<?> actualValue = execDslImmediately(configDsl, configKey.getType(), app, true);
        assertTrue(actualValue.isAbsent());
    }

    @Test
    public void testSelf() throws Exception {
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.self();
        new SelfTestWorker(app, dsl).run();
    }

    @Test(groups="Integration")
    public void testSelfConcurrent() throws Exception {
        final BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.self();
        runConcurrentWorker(new Supplier<Runnable>() {
            @Override
            public Runnable get() {
                return new SelfTestWorker(app, dsl);
            }
        });
    }

    @Test
    public void testParent() throws Exception {
        BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.parent();
        new ParentTestWorker(app, dsl).run();
    }

    @Test(groups="Integration")
    public void testParentConcurrent() throws Exception {
        final BrooklynDslDeferredSupplier<?> dsl = BrooklynDslCommon.parent();
        runConcurrentWorker(new Supplier<Runnable>() {
            @Override
            public Runnable get() {
                return new ParentTestWorker(app, dsl);
            }
        });
    }

    protected void runConcurrentWorker(Supplier<Runnable> taskSupplier) {
        Collection<Task<?>> results = new ArrayList<>();
        for (int i = 0; i < MAX_PARALLEL_RESOLVERS; i++) {
            Task<?> result = mgmt.getExecutionManager().submit(taskSupplier.get());
            results.add(result);
        }
        for (Task<?> result : results) {
            result.getUnchecked();
        }
    }
    
    private static class DslTestWorker implements Runnable {
        protected final TestApplication parent;
        protected final BrooklynDslDeferredSupplier<?> dsl;
        protected final Class<?> type;
        protected EntitySpec<TestEntity> childSpec = EntitySpec.create(TestEntity.class);
        protected int resolverIterations = MANY_RESOLVER_ITERATIONS;
        protected boolean satisfiedAsynchronously = false;
        private boolean wrapInTaskForImmediately = true;
        
        public DslTestWorker(TestApplication parent, BrooklynDslDeferredSupplier<?> dsl, Class<?> type) {
            this.parent = checkNotNull(parent, "parent");
            this.dsl = checkNotNull(dsl, "dsl");
            this.type = checkNotNull(type, "type");
        }

        public DslTestWorker resolverIterations(int val) {
            resolverIterations = val;
            return this;
        }
        
        public DslTestWorker satisfiedAsynchronously(boolean val) {
            satisfiedAsynchronously = val;
            return this;
        }
        
        public DslTestWorker wrapInTaskForImmediately(boolean val) {
            wrapInTaskForImmediately = val;
            return this;
        }
        
        @Override
        public void run() {
            try {
                TestEntity entity = parent.addChild(childSpec);
                for (int i = 0; i < resolverIterations; i++) {
                    // Call dsl.getImmediately()
                    preResolve(entity);
                    Maybe<?> immediateValue;
                    try {
                        immediateValue = execDslImmediately(dsl, type, entity, wrapInTaskForImmediately);
                    } catch (Exception e) {
                        throw Exceptions.propagate(e);
                    }
                    postResolve(entity, immediateValue, true);
                    
                    // Call dsl.get()
                    preResolve(entity);
                    Maybe<?> eventualValue = execDslEventually(dsl, type, entity, Duration.ONE_MINUTE);
                    postResolve(entity, eventualValue, false);
                }
            } catch (Exception e) {
                Exceptions.propagate(e);
            }
        }

        protected void preResolve(TestEntity entity) throws Exception {
        }

        protected void postResolve(TestEntity entity, Maybe<?> actualValue, boolean isImmediate) throws Exception {
        }
    }

    private class AttributeWhenReadyTestWorker extends DslTestWorker {
        private AttributeSensor<String> sensor;
        private String expectedValue;
        private ListenableScheduledFuture<?> future;

        public AttributeWhenReadyTestWorker(TestApplication parent, AttributeSensor<String> sensor, BrooklynDslDeferredSupplier<?> dsl) {
            super(parent, dsl, sensor.getType());
            this.sensor = sensor;
        }

        @Override
        protected void preResolve(final TestEntity entity) {
            expectedValue = Identifiers.makeRandomId(10);
            Runnable job = new Runnable() {
                public void run() {
                    entity.sensors().set(sensor, expectedValue);
                }
            };
            if (satisfiedAsynchronously) {
                future = executor.schedule(job, random.nextInt(20), TimeUnit.MILLISECONDS);
            } else {
                job.run();
            }
        }

        @Override
        protected void postResolve(TestEntity entity, Maybe<?> actualValue, boolean isImmediate) throws Exception {
            if (satisfiedAsynchronously && isImmediate) {
                // We accept a maybe.absent if we called getImmediately when satisfiedAsynchronously
                assertTrue(actualValue.isAbsent() || expectedValue.equals(actualValue.get()), "actual="+actualValue+"; expected="+expectedValue);
            } else {
                assertEquals(actualValue.get(), expectedValue);
            }
            
            if (future != null) {
                future.get(Asserts.DEFAULT_LONG_TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS);
                future = null;
            }
            // Reset sensor - otherwise if run in a loop the old value will be picked up, before our execute sets the new value
            entity.sensors().set(sensor, null);
        }
    }

    private static class SelfTestWorker extends DslTestWorker {
        public SelfTestWorker(TestApplication parent, BrooklynDslDeferredSupplier<?> dsl) {
            super(parent, dsl, Entity.class);
        }

        @Override
        protected void preResolve(TestEntity entity) {
        }

        @Override
        protected void postResolve(TestEntity entity, Maybe<?> actualValue, boolean isImmediate) {
            assertEquals(actualValue.get(), entity);
        }

    }

    private static class ParentTestWorker extends DslTestWorker {
        public ParentTestWorker(TestApplication parent, BrooklynDslDeferredSupplier<?> dsl) {
            super(parent, dsl, Entity.class);
        }

        @Override
        protected void postResolve(TestEntity entity, Maybe<?> actualValue, boolean isImmediate) {
            assertEquals(actualValue.get(), parent);
        }
    }
    
    private class ConfigTestWorker extends DslTestWorker {
        private ConfigKey<?> config;
        private Object expectedValue;
        private Function<? super Entity, ConfigValuePair> valueFunction;
        
        public ConfigTestWorker(TestApplication parent, ConfigKey<?> config, BrooklynDslDeferredSupplier<?> dsl) {
            this(parent, config, newRandomConfigValueSupplier(), dsl);
        }

        public ConfigTestWorker(TestApplication parent, ConfigKey<?> config, Supplier<ConfigValuePair> valueSupplier, BrooklynDslDeferredSupplier<?> dsl) {
            this(parent, config, Functions.forSupplier(valueSupplier), dsl);
        }
        
        public ConfigTestWorker(TestApplication parent, ConfigKey<?> config, Function<? super Entity, ConfigValuePair> valueFunction, BrooklynDslDeferredSupplier<?> dsl) {
            super(parent, dsl, config.getType());
            this.config = config;
            this.valueFunction = valueFunction;
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        protected void preResolve(final TestEntity entity) {
            ConfigValuePair pair = valueFunction.apply(entity);
            expectedValue = pair.expectedResolvedVal;
            entity.config().set((ConfigKey)config, pair.configVal); // nasty cast, because val might be a DSL
        }

        @Override
        @SuppressWarnings({ "rawtypes", "unchecked" })
        protected void postResolve(TestEntity entity, Maybe<?> actualValue, boolean isImmediate) throws Exception {
            if (satisfiedAsynchronously && isImmediate) {
                // We accept a maybe.absent if we called getImmediately when satisfiedAsynchronously
                assertTrue(actualValue.isAbsent() || expectedValue.equals(actualValue.get()), "actual="+actualValue+"; expected="+expectedValue);
            } else {
                assertEquals(actualValue.get(), expectedValue);
            }
            
            // Reset config - otherwise if run in a loop the old value will be picked up, before our execute sets the new value
            entity.config().set((ConfigKey)config, (Object)null); // ugly cast from ConfigKey<?>
        }
    }

    static class ConfigValuePair {
        public final Object configVal;
        public final Object expectedResolvedVal;
        
        public ConfigValuePair(Object configVal, Object expectedResolvedVal) {
            this.configVal = configVal;
            this.expectedResolvedVal = expectedResolvedVal;
        }
    }

    private static Supplier<ConfigValuePair> newRandomConfigValueSupplier() {
        return new Supplier<ConfigValuePair>() {
            @Override public ConfigValuePair get() {
                String val = Identifiers.makeRandomId(10);
                return new ConfigValuePair(val, val);
            }
        };
    }

    static Maybe<?> execDslImmediately(final BrooklynDslDeferredSupplier<?> dsl, final Class<?> type, final Entity context, boolean execInTask) throws Exception {
        // Exec'ing immediately will call DSL in current thread. It needs to find the context entity,
        // and does this using BrooklynTaskTags.getTargetOrContextEntity(Tasks.current()).
        // If we are not in a task executed by the context entity, then this lookup will fail. 
        Callable<Maybe<?>> job = new Callable<Maybe<?>>() {
            public Maybe<?> call() throws Exception {
                return Tasks.resolving(dsl).as(type)
                        .context(context)
                        .description("Computing "+dsl)
                        .immediately(true)
                        .getMaybe();
            }
        };
        if (execInTask) {
            Task<Maybe<?>> task = ((EntityInternal)context).getExecutionContext().submit(job);
            task.get(Asserts.DEFAULT_LONG_TIMEOUT);
            assertTrue(task.isDone());
            return task.get();
            
        } else {
            return job.call();
        }
    }
    
    static Maybe<?> execDslRealRealQuick(BrooklynDslDeferredSupplier<?> dsl, Class<?> type, Entity context) {
        return execDslEventually(dsl, type, context, ValueResolver.REAL_REAL_QUICK_WAIT);
    }
    
    static Maybe<?> execDslEventually(BrooklynDslDeferredSupplier<?> dsl, Class<?> type, Entity context, Duration timeout) {
        return Tasks.resolving(dsl).as(type)
                .context(context)
                .description("Computing "+dsl)
                .timeout(timeout)
                .getMaybe();
    }
}
