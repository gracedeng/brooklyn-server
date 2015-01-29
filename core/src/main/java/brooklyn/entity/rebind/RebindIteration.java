/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.entity.rebind;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.basic.AbstractBrooklynObject;
import brooklyn.basic.BrooklynObject;
import brooklyn.basic.BrooklynObjectInternal;
import brooklyn.catalog.BrooklynCatalog;
import brooklyn.catalog.CatalogItem;
import brooklyn.catalog.CatalogLoadMode;
import brooklyn.catalog.internal.BasicBrooklynCatalog;
import brooklyn.catalog.internal.CatalogUtils;
import brooklyn.config.BrooklynLogging;
import brooklyn.config.BrooklynLogging.LoggingLevel;
import brooklyn.config.BrooklynServerConfig;
import brooklyn.enricher.basic.AbstractEnricher;
import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.Feed;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.proxying.InternalEntityFactory;
import brooklyn.entity.proxying.InternalFactory;
import brooklyn.entity.proxying.InternalLocationFactory;
import brooklyn.entity.proxying.InternalPolicyFactory;
import brooklyn.entity.rebind.RebindManagerImpl.RebindTracker;
import brooklyn.entity.rebind.persister.PersistenceActivityMetrics;
import brooklyn.event.feed.AbstractFeed;
import brooklyn.internal.BrooklynFeatureEnablement;
import brooklyn.location.Location;
import brooklyn.location.basic.AbstractLocation;
import brooklyn.location.basic.LocationInternal;
import brooklyn.management.classloading.BrooklynClassLoadingContext;
import brooklyn.management.ha.ManagementNodeState;
import brooklyn.management.internal.EntityManagerInternal;
import brooklyn.management.internal.LocationManagerInternal;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.management.internal.ManagementTransitionInfo.ManagementTransitionMode;
import brooklyn.mementos.BrooklynMemento;
import brooklyn.mementos.BrooklynMementoManifest;
import brooklyn.mementos.BrooklynMementoManifest.EntityMementoManifest;
import brooklyn.mementos.BrooklynMementoPersister;
import brooklyn.mementos.BrooklynMementoPersister.LookupContext;
import brooklyn.mementos.BrooklynMementoRawData;
import brooklyn.mementos.CatalogItemMemento;
import brooklyn.mementos.EnricherMemento;
import brooklyn.mementos.EntityMemento;
import brooklyn.mementos.FeedMemento;
import brooklyn.mementos.LocationMemento;
import brooklyn.mementos.Memento;
import brooklyn.mementos.PolicyMemento;
import brooklyn.mementos.TreeNode;
import brooklyn.policy.Enricher;
import brooklyn.policy.Policy;
import brooklyn.policy.basic.AbstractPolicy;
import brooklyn.util.collections.MutableList;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.FlagUtils;
import brooklyn.util.guava.Maybe;
import brooklyn.util.javalang.Reflections;
import brooklyn.util.text.Strings;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
Multi-phase deserialization:

<ul>
<li> 1. load the manifest files and populate the summaries (ID+type) in {@link BrooklynMementoManifest}
<li> 2. instantiate and reconstruct catalog items
<li> 3. instantiate entities+locations -- so that inter-entity references can subsequently 
       be set during deserialize (and entity config/state is set).
<li> 4. deserialize the manifests to instantiate the mementos
<li> 5. instantiate policies+enrichers+feeds 
        (could probably merge this with (3), depending how they are implemented)
<li> 6. reconstruct the locations, policies, etc, then finally entities -- setting all fields and then calling 
        {@link RebindSupport#reconstruct(RebindContext, Memento)}
<li> 7. associate policies+enrichers+feeds to all the entities
<li> 8. manage the entities
</ul>

 If underlying data-store is changed between first and second manifest read (e.g. to add an
 entity), then second phase might try to reconstitute an entity that has not been put in
 the rebindContext. This should not affect normal production usage, because rebind is run
 against a data-store that is not being written to by other brooklyn instance(s).
 But clearly it would be desirable to have better locking possible against the backing store.
 
<p>
 When rebinding to code in OSGi bundles, thecatalog item id context is inferred as follows:
   * most of the time the creator will be passing "my catalog item id" 
     (or API could also take "BrooklynObject me" as a creation context and the 
     receiver query the creator's catalog item id)
   * look at the context entity of Tasks.current() (if set)
   * propagate the catalog item id when doing setEntity, addChild
   * when none of the above work (or they are wrong) let the user specify the catalog item
<p>
  Precedence of setting the catalog item ID:
   1. User-supplied catalog item ID.
   2. Creating from a catalog item - all items resolved during the creation of a spec
      from a catalog item receive the catalog item's ID as context.
   3. When using the Java API for creating specs get the catalog item ID from the
      context entity of the Tasks.current() task.
   4. Propagate the context catalog item ID to children, adjuncts if they don't have one already.
*/
public class RebindIteration {

    private static final Logger LOG = LoggerFactory.getLogger(RebindIteration.class);
    
    private final RebindManagerImpl rebindManager;
    
    private final ClassLoader classLoader;
    private final RebindExceptionHandler exceptionHandler;
    private final ManagementNodeState mode;
    private final ManagementContextInternal managementContext;

    private final Semaphore rebindActive; 
    private final AtomicInteger readOnlyRebindCount;
    private final PersistenceActivityMetrics rebindMetrics;
    private final BrooklynMementoPersister persistenceStoreAccess;
    
    private final AtomicBoolean iterationStarted = new AtomicBoolean();
    private final RebindContextImpl rebindContext;
    private final Reflections reflections;
    private final LookupContext lookupContext;
    private final BrooklynObjectInstantiator instantiator;
    
    // populated in the course of a run
    
    // set on run start
    
    private Stopwatch timer;
    /** phase is used to ensure our steps are run as we've expected, and documented (in javadoc at top).
     * it's worth the extra effort due to the complication and the subtleties. */
    private int phase = 0;

    // set in first phase
    
    private BrooklynMementoRawData mementoRawData;
    private BrooklynMementoManifest mementoManifest;
    private Boolean overwritingMaster;
    private Boolean isEmpty;

    // set later on
    
    private BrooklynMemento memento;

    // set near the end
    
    private List<Application> applications;

    
    public RebindIteration(RebindManagerImpl rebindManager, 
            ManagementNodeState mode,
            ClassLoader classLoader, RebindExceptionHandler exceptionHandler,
            Semaphore rebindActive, AtomicInteger readOnlyRebindCount, PersistenceActivityMetrics rebindMetrics, BrooklynMementoPersister persistenceStoreAccess
            ) {
        // NB: there is no particularly deep meaning in what is passed in vs what is lookup up from the RebindManager which calls us
        // (this is simply a refactoring of previous code to a new class)
        
        this.rebindManager = rebindManager;
        
        this.mode = mode;
        this.classLoader = checkNotNull(classLoader, "classLoader");
        this.exceptionHandler = checkNotNull(exceptionHandler, "exceptionHandler");
        
        this.rebindActive = rebindActive; 
        this.readOnlyRebindCount = readOnlyRebindCount;
        this.rebindMetrics = rebindMetrics;
        this.persistenceStoreAccess = persistenceStoreAccess;
        
        managementContext = rebindManager.getManagementContext();
        rebindContext = new RebindContextImpl(exceptionHandler, classLoader);
        reflections = new Reflections(classLoader);
        lookupContext = new RebindContextLookupContext(managementContext, rebindContext, exceptionHandler);
        rebindContext.setLookupContext(lookupContext);
        instantiator = new BrooklynObjectInstantiator(classLoader, rebindContext, reflections);
        
        if (mode==ManagementNodeState.HOT_STANDBY || mode==ManagementNodeState.HOT_BACKUP) {
            rebindContext.setAllReadOnly();
        } else {
            Preconditions.checkState(mode==ManagementNodeState.MASTER, "Must be either master or read only to rebind (mode "+mode+")");
        }
    }

    public List<Application> getApplications() {
        return applications;
    }
    
    RebindContextImpl getRebindContext() {
        return rebindContext;
    }
    
    public void runFullRebind() {
        runWithLock(new Callable<Void>() {
           public Void call() throws Exception {
               
               loadManifestFiles();
               rebuildCatalog();
               instantiateLocationsAndEntities();
               instantiateMementos();
               instantiateAdjuncts(instantiator); 
               reconstructEverything();
               associateAdjunctsWithEntities();
               manageTheObjects();
               finishingUp();
               
               return null;
           }
        });
    }
    
    protected void runWithLock(Callable<?> target) {
        if (iterationStarted.getAndSet(true)) {
            throw new IllegalStateException("Iteration "+this+" has already run; create a new instance for another rebind pass.");
        }
        try {
            rebindActive.acquire();
        } catch (InterruptedException e) { Exceptions.propagate(e); }
        try {
            RebindTracker.setRebinding();
            if (ManagementNodeState.isHotProxy(mode))
                readOnlyRebindCount.incrementAndGet();

            timer = Stopwatch.createStarted();
            exceptionHandler.onStart(rebindContext);

            target.call();
            
            exceptionHandler.onDone();
            
            rebindMetrics.noteSuccess(Duration.of(timer));
            noteErrors(exceptionHandler, null);
            
        } catch (Exception e) {
            rebindMetrics.noteFailure(Duration.of(timer));
            
            Exceptions.propagateIfFatal(e);
            noteErrors(exceptionHandler, e);
            throw exceptionHandler.onFailed(e);
            
        } finally {
            rebindActive.release();
            RebindTracker.reset();
        }
    }
    
    protected void checkEnteringPhase(int targetPhase) {
        phase++;
        checkContinuingPhase(targetPhase);
    }
    protected void checkContinuingPhase(int targetPhase) {
        if (targetPhase!=phase)
            throw new IllegalStateException("Phase mismatch: should be phase "+targetPhase+" but is currently "+phase);
    }
    
    protected void loadManifestFiles() throws Exception {
        checkEnteringPhase(1);

        //The manifest contains full catalog items mementos. Reading them at this stage means that
        //we don't support references to entities/locations withing tags.

        LOG.debug("Rebinding ("+mode+
            (readOnlyRebindCount.get()>Integer.MIN_VALUE ? ", iteration "+readOnlyRebindCount : "")+
            ") from "+rebindManager.getPersister().getBackingStoreDescription()+"...");

        if (mementoRawData!=null || mementoManifest!=null)
            throw new IllegalStateException("Memento data is already set");
        
        mementoRawData = persistenceStoreAccess.loadMementoRawData(exceptionHandler);
        mementoManifest = persistenceStoreAccess.loadMementoManifest(mementoRawData, exceptionHandler);

        determineStateFromManifestFiles();
    }

    protected void determineStateFromManifestFiles() {
        checkContinuingPhase(1);

        overwritingMaster = false;
        isEmpty = mementoManifest.isEmpty();

        if (mode!=ManagementNodeState.HOT_STANDBY && mode!=ManagementNodeState.HOT_BACKUP) {
            if (!isEmpty) { 
                LOG.info("Rebinding from "+getPersister().getBackingStoreDescription()+" for "+Strings.toLowerCase(Strings.toString(mode))+" "+managementContext.getManagementNodeId()+"...");
            } else {
                LOG.info("Rebind check: no existing state; will persist new items to "+getPersister().getBackingStoreDescription());
            }

            if (!managementContext.getEntityManager().getEntities().isEmpty() || !managementContext.getLocationManager().getLocations().isEmpty()) {
                // this is discouraged if we were already master
                Entity anEntity = Iterables.getFirst(managementContext.getEntityManager().getEntities(), null);
                if (anEntity!=null && !((EntityInternal)anEntity).getManagementSupport().isReadOnly()) {
                    overwritingMaster = true;
                    LOG.warn("Rebind requested for "+mode+" node "+managementContext.getManagementNodeId()+" "
                        + "when it already has active state; discouraged, "
                        + "will likely overwrite: "+managementContext.getEntityManager().getEntities()+" and "+managementContext.getLocationManager().getLocations()+" and more");
                }
            }
        }
    }

    private void rebuildCatalog() {
        
        // build catalog early so we can load other things
        checkEnteringPhase(2);
        
        // Instantiate catalog items
        if (rebindManager.persistCatalogItemsEnabled) {
            logRebindingDebug("RebindManager instantiating catalog items: {}", mementoManifest.getCatalogItemIds());
            for (CatalogItemMemento catalogItemMemento : mementoManifest.getCatalogItemMementos().values()) {
                if (LOG.isDebugEnabled()) LOG.debug("RebindManager instantiating catalog item {}", catalogItemMemento);
                try {
                    CatalogItem<?, ?> catalogItem = instantiator.newCatalogItem(catalogItemMemento);
                    rebindContext.registerCatalogItem(catalogItemMemento.getId(), catalogItem);
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.CATALOG_ITEM, catalogItemMemento.getId(), catalogItemMemento.getType(), e);
                }
            }
        } else {
            logRebindingDebug("Not rebinding catalog; feature disabled: {}", mementoManifest.getCatalogItemIds());
        }

        // Reconstruct catalog entries
        if (rebindManager.persistCatalogItemsEnabled) {
            logRebindingDebug("RebindManager reconstructing catalog items");
            for (CatalogItemMemento catalogItemMemento : mementoManifest.getCatalogItemMementos().values()) {
                CatalogItem<?, ?> item = rebindContext.getCatalogItem(catalogItemMemento.getId());
                logRebindingDebug("RebindManager reconstructing catalog item {}", catalogItemMemento);
                if (item == null) {
                    exceptionHandler.onNotFound(BrooklynObjectType.CATALOG_ITEM, catalogItemMemento.getId());
                } else {
                    try {
                        item.getRebindSupport().reconstruct(rebindContext, catalogItemMemento);
                        if (item instanceof AbstractBrooklynObject) {
                            AbstractBrooklynObject.class.cast(item).setManagementContext(managementContext);
                        }
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.CATALOG_ITEM, item, e);
                    }
                }
            }
        }
        
        // Register catalogue items with the management context. Loads the bundles in the OSGi framework.
        CatalogLoadMode catalogLoadMode = managementContext.getConfig().getConfig(BrooklynServerConfig.CATALOG_LOAD_MODE);
        if (rebindManager.persistCatalogItemsEnabled) {
            boolean shouldResetCatalog = catalogLoadMode == CatalogLoadMode.LOAD_PERSISTED_STATE
                    || (!isEmpty && catalogLoadMode == CatalogLoadMode.LOAD_BROOKLYN_CATALOG_URL_IF_NO_PERSISTED_STATE);
            boolean shouldLoadDefaultCatalog = catalogLoadMode == CatalogLoadMode.LOAD_BROOKLYN_CATALOG_URL
                    || (isEmpty && catalogLoadMode == CatalogLoadMode.LOAD_BROOKLYN_CATALOG_URL_IF_NO_PERSISTED_STATE);
            if (shouldResetCatalog) {
                // Reset catalog with previously persisted state
                logRebindingDebug("RebindManager resetting management context catalog to previously persisted state");
                managementContext.getCatalog().reset(rebindContext.getCatalogItems());
            } else if (shouldLoadDefaultCatalog) {
                // Load catalogue as normal
                // TODO in read-only mode, should do this less frequently than entities etc
                logRebindingDebug("RebindManager loading default catalog");
                ((BasicBrooklynCatalog) managementContext.getCatalog()).resetCatalogToContentsAtConfiguredUrl();
            } else {
                // Management context should have taken care of loading the catalogue
                Collection<CatalogItem<?, ?>> catalogItems = rebindContext.getCatalogItems();
                String message = "RebindManager not resetting catalog to persisted state. Catalog load mode is {}.";
                if (!catalogItems.isEmpty() && shouldLogRebinding()) {
                    LOG.info(message + " There {} {} item{} persisted.", new Object[]{
                            catalogLoadMode, catalogItems.size() == 1 ? "was" : "were", catalogItems.size(), Strings.s(catalogItems)});
                } else if (LOG.isDebugEnabled()) {
                    logRebindingDebug(message, catalogLoadMode);
                }
            }
            // TODO destroy old (as above)
        } else {
            logRebindingDebug("RebindManager not resetting catalog because catalog persistence is disabled");
        }
    }

    private void instantiateLocationsAndEntities() {

        checkEnteringPhase(3);
        
        // Instantiate locations
        logRebindingDebug("RebindManager instantiating locations: {}", mementoManifest.getLocationIdToType().keySet());
        for (Map.Entry<String, String> entry : mementoManifest.getLocationIdToType().entrySet()) {
            String locId = entry.getKey();
            String locType = entry.getValue();
            if (LOG.isTraceEnabled()) LOG.trace("RebindManager instantiating location {}", locId);
            
            try {
                Location location = instantiator.newLocation(locId, locType);
                rebindContext.registerLocation(locId, location);
            } catch (Exception e) {
                exceptionHandler.onCreateFailed(BrooklynObjectType.LOCATION, locId, locType, e);
            }
        }
        
        // Instantiate entities
        logRebindingDebug("RebindManager instantiating entities: {}", mementoManifest.getEntityIdToManifest().keySet());
        for (Map.Entry<String, EntityMementoManifest> entry : mementoManifest.getEntityIdToManifest().entrySet()) {
            String entityId = entry.getKey();
            EntityMementoManifest entityManifest = entry.getValue();
            String catalogItemId = findCatalogItemId(classLoader, mementoManifest.getEntityIdToManifest(), entityManifest);
            
            if (LOG.isTraceEnabled()) LOG.trace("RebindManager instantiating entity {}", entityId);
            
            try {
                Entity entity = (Entity) instantiator.newEntity(entityId, entityManifest.getType(), catalogItemId);
                ((EntityInternal)entity).getManagementSupport().setReadOnly( rebindContext.isReadOnly(entity) );
                rebindContext.registerEntity(entityId, entity);

            } catch (Exception e) {
                exceptionHandler.onCreateFailed(BrooklynObjectType.ENTITY, entityId, entityManifest.getType(), e);
            }
        }
    }

    private void instantiateMementos() throws IOException {
        
        checkEnteringPhase(4);
        
        memento = persistenceStoreAccess.loadMemento(mementoRawData, lookupContext, exceptionHandler);
    }

    private void instantiateAdjuncts(BrooklynObjectInstantiator instantiator) {
        
        checkEnteringPhase(5);
        
        // Instantiate policies
        if (rebindManager.persistPoliciesEnabled) {
            logRebindingDebug("RebindManager instantiating policies: {}", memento.getPolicyIds());
            for (PolicyMemento policyMemento : memento.getPolicyMementos().values()) {
                logRebindingDebug("RebindManager instantiating policy {}", policyMemento);
                
                try {
                    Policy policy = instantiator.newPolicy(policyMemento);
                    rebindContext.registerPolicy(policyMemento.getId(), policy);
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.POLICY, policyMemento.getId(), policyMemento.getType(), e);
                }
            }
        } else {
            logRebindingDebug("Not rebinding policies; feature disabled: {}", memento.getPolicyIds());
        }
        
        // Instantiate enrichers
        if (rebindManager.persistEnrichersEnabled) {
            logRebindingDebug("RebindManager instantiating enrichers: {}", memento.getEnricherIds());
            for (EnricherMemento enricherMemento : memento.getEnricherMementos().values()) {
                logRebindingDebug("RebindManager instantiating enricher {}", enricherMemento);

                try {
                    Enricher enricher = instantiator.newEnricher(enricherMemento);
                    rebindContext.registerEnricher(enricherMemento.getId(), enricher);
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.ENRICHER, enricherMemento.getId(), enricherMemento.getType(), e);
                }
            }
        } else {
            logRebindingDebug("Not rebinding enrichers; feature disabled: {}", memento.getEnricherIds());
        } 
        
        // Instantiate feeds
        if (rebindManager.persistFeedsEnabled) {
            logRebindingDebug("RebindManager instantiating feeds: {}", memento.getFeedIds());
            for (FeedMemento feedMemento : memento.getFeedMementos().values()) {
                if (LOG.isDebugEnabled()) LOG.debug("RebindManager instantiating feed {}", feedMemento);

                try {
                    Feed feed = instantiator.newFeed(feedMemento);
                    rebindContext.registerFeed(feedMemento.getId(), feed);
                } catch (Exception e) {
                    exceptionHandler.onCreateFailed(BrooklynObjectType.FEED, feedMemento.getId(), feedMemento.getType(), e);
                }
            }
        } else {
            logRebindingDebug("Not rebinding feeds; feature disabled: {}", memento.getFeedIds());
        }
    }

    private void reconstructEverything() {
        
        checkEnteringPhase(6);
        
        // Reconstruct locations
        logRebindingDebug("RebindManager reconstructing locations");
        for (LocationMemento locMemento : sortParentFirst(memento.getLocationMementos()).values()) {
            Location location = rebindContext.getLocation(locMemento.getId());
            logRebindingDebug("RebindManager reconstructing location {}", locMemento);
            if (location == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.LOCATION, locMemento.getId());
            } else {
                try {
                    ((LocationInternal)location).getRebindSupport().reconstruct(rebindContext, locMemento);
                } catch (Exception e) {
                    exceptionHandler.onRebindFailed(BrooklynObjectType.LOCATION, location, e);
                }
            }
        }

        // Reconstruct policies
        if (rebindManager.persistPoliciesEnabled) {
            logRebindingDebug("RebindManager reconstructing policies");
            for (PolicyMemento policyMemento : memento.getPolicyMementos().values()) {
                Policy policy = rebindContext.getPolicy(policyMemento.getId());
                logRebindingDebug("RebindManager reconstructing policy {}", policyMemento);
   
                if (policy == null) {
                    // usually because of creation-failure, when not using fail-fast
                    exceptionHandler.onNotFound(BrooklynObjectType.POLICY, policyMemento.getId());
                } else {
                    try {
                        policy.getRebindSupport().reconstruct(rebindContext, policyMemento);
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.POLICY, policy, e);
                        rebindContext.unregisterPolicy(policy);
                    }
                }
            }
        }

        // Reconstruct enrichers
        if (rebindManager.persistEnrichersEnabled) {
            logRebindingDebug("RebindManager reconstructing enrichers");
            for (EnricherMemento enricherMemento : memento.getEnricherMementos().values()) {
                Enricher enricher = rebindContext.getEnricher(enricherMemento.getId());
                logRebindingDebug("RebindManager reconstructing enricher {}", enricherMemento);
      
                if (enricher == null) {
                    // usually because of creation-failure, when not using fail-fast
                    exceptionHandler.onNotFound(BrooklynObjectType.ENRICHER, enricherMemento.getId());
                } else {
                    try {
                        enricher.getRebindSupport().reconstruct(rebindContext, enricherMemento);
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.ENRICHER, enricher, e);
                        rebindContext.unregisterEnricher(enricher);
                    }
                }
            }
        }
   
        // Reconstruct feeds
        if (rebindManager.persistFeedsEnabled) {
            logRebindingDebug("RebindManager reconstructing feeds");
            for (FeedMemento feedMemento : memento.getFeedMementos().values()) {
                Feed feed = rebindContext.getFeed(feedMemento.getId());
                logRebindingDebug("RebindManager reconstructing feed {}", feedMemento);
      
                if (feed == null) {
                    // usually because of creation-failure, when not using fail-fast
                    exceptionHandler.onNotFound(BrooklynObjectType.FEED, feedMemento.getId());
                } else {
                    try {
                        feed.getRebindSupport().reconstruct(rebindContext, feedMemento);
                    } catch (Exception e) {
                        exceptionHandler.onRebindFailed(BrooklynObjectType.FEED, feed, e);
                        rebindContext.unregisterFeed(feed);
                    }
                }

            }
        }
   
        // Reconstruct entities
        logRebindingDebug("RebindManager reconstructing entities");
        for (EntityMemento entityMemento : sortParentFirst(memento.getEntityMementos()).values()) {
            Entity entity = rebindContext.lookup().lookupEntity(entityMemento.getId());
            logRebindingDebug("RebindManager reconstructing entity {}", entityMemento);
   
            if (entity == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.ENTITY, entityMemento.getId());
            } else {
                try {
                    entityMemento.injectTypeClass(entity.getClass());
                    ((EntityInternal)entity).getRebindSupport().reconstruct(rebindContext, entityMemento);
                } catch (Exception e) {
                    exceptionHandler.onRebindFailed(BrooklynObjectType.ENTITY, entity, e);
                }
            }
        }
    }

    private void associateAdjunctsWithEntities() {
        
        checkEnteringPhase(7);

        logRebindingDebug("RebindManager associating adjuncts to entities");
        for (EntityMemento entityMemento : sortParentFirst(memento.getEntityMementos()).values()) {
            Entity entity = rebindContext.getEntity(entityMemento.getId());
            logRebindingDebug("RebindManager associating adjuncts to entity {}", entityMemento);
   
            if (entity == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.ENTITY, entityMemento.getId());
            } else {
                try {
                    entityMemento.injectTypeClass(entity.getClass());
                    // TODO these call to the entity which in turn sets the entity on the underlying feeds and enrichers;
                    // that is taken as the cue to start, but it should not be. start should be a separate call.
                    ((EntityInternal)entity).getRebindSupport().addPolicies(rebindContext, entityMemento);
                    ((EntityInternal)entity).getRebindSupport().addEnrichers(rebindContext, entityMemento);
                    ((EntityInternal)entity).getRebindSupport().addFeeds(rebindContext, entityMemento);
                } catch (Exception e) {
                    exceptionHandler.onRebindFailed(BrooklynObjectType.ENTITY, entity, e);
                }
            }
        }
    }

    private void manageTheObjects() {

        checkEnteringPhase(8);
        
        logRebindingDebug("RebindManager managing locations");
        LocationManagerInternal locationManager = (LocationManagerInternal)managementContext.getLocationManager();
        Set<String> oldLocations = Sets.newLinkedHashSet(locationManager.getLocationIds());
        for (Location location: rebindContext.getLocations()) {
            ManagementTransitionMode oldMode = locationManager.getLastManagementTransitionMode(location.getId());
            locationManager.setManagementTransitionMode(location, RebindManagerImpl.computeMode(managementContext, location, oldMode, rebindContext.isReadOnly(location)) );
            if (oldMode!=null)
                oldLocations.remove(location.getId());
        }
        for (Location location: rebindContext.getLocations()) {
            if (location.getParent()==null) {
                // manage all root locations
                try {
                    ((LocationManagerInternal)managementContext.getLocationManager()).manageRebindedRoot(location);
                } catch (Exception e) {
                    exceptionHandler.onManageFailed(BrooklynObjectType.LOCATION, location, e);
                }
            }
        }
        // destroy old
        if (!oldLocations.isEmpty()) BrooklynLogging.log(LOG, overwritingMaster ? BrooklynLogging.LoggingLevel.WARN : BrooklynLogging.LoggingLevel.DEBUG, 
            "Destroying unused locations on rebind: "+oldLocations);
        for (String oldLocationId: oldLocations) {
           locationManager.unmanage(locationManager.getLocation(oldLocationId), ManagementTransitionMode.REBINDING_DESTROYED); 
        }
        
        // Manage the top-level apps (causing everything under them to become managed)
        logRebindingDebug("RebindManager managing entities");
        EntityManagerInternal entityManager = (EntityManagerInternal)managementContext.getEntityManager();
        Set<String> oldEntities = Sets.newLinkedHashSet(entityManager.getEntityIds());
        for (Entity entity: rebindContext.getEntities()) {
            ManagementTransitionMode oldMode = entityManager.getLastManagementTransitionMode(entity.getId());
            entityManager.setManagementTransitionMode(entity, RebindManagerImpl.computeMode(managementContext,entity, oldMode, rebindContext.isReadOnly(entity)) );
            if (oldMode!=null)
                oldEntities.remove(entity.getId());
        }
        List<Application> apps = Lists.newArrayList();
        for (String appId : memento.getApplicationIds()) {
            Entity entity = rebindContext.getEntity(appId);
            if (entity == null) {
                // usually because of creation-failure, when not using fail-fast
                exceptionHandler.onNotFound(BrooklynObjectType.ENTITY, appId);
            } else {
                try {
                    entityManager.manageRebindedRoot(entity);
                } catch (Exception e) {
                    exceptionHandler.onManageFailed(BrooklynObjectType.ENTITY, entity, e);
                }
                apps.add((Application)entity);
            }
        }
        // destroy old
        if (!oldLocations.isEmpty()) BrooklynLogging.log(LOG, overwritingMaster ? BrooklynLogging.LoggingLevel.WARN : BrooklynLogging.LoggingLevel.DEBUG, 
            "Destroying unused entities on rebind: "+oldEntities);
        for (String oldEntityId: oldEntities) {
           entityManager.unmanage(entityManager.getEntity(oldEntityId), ManagementTransitionMode.REBINDING_DESTROYED); 
        }

        this.applications = apps;
    }

    private void finishingUp() {
        
        checkContinuingPhase(8);
        
        if (!isEmpty) {
            BrooklynLogging.log(LOG, shouldLogRebinding() ? LoggingLevel.INFO : LoggingLevel.DEBUG, 
                "Rebind complete " + "("+mode+(readOnlyRebindCount.get()>=0 ? ", iteration "+readOnlyRebindCount : "")+")" +
                    " in {}: {} app{}, {} entit{}, {} location{}, {} polic{}, {} enricher{}, {} feed{}, {} catalog item{}", new Object[]{
                Time.makeTimeStringRounded(timer), applications.size(), Strings.s(applications),
                rebindContext.getEntities().size(), Strings.ies(rebindContext.getEntities()),
                rebindContext.getLocations().size(), Strings.s(rebindContext.getLocations()),
                rebindContext.getPolicies().size(), Strings.ies(rebindContext.getPolicies()),
                rebindContext.getEnrichers().size(), Strings.s(rebindContext.getEnrichers()),
                rebindContext.getFeeds().size(), Strings.s(rebindContext.getFeeds()),
                rebindContext.getCatalogItems().size(), Strings.s(rebindContext.getCatalogItems())
            });
        }

        // Return the top-level applications
        logRebindingDebug("RebindManager complete; apps: {}", memento.getApplicationIds());
    }

    private void noteErrors(final RebindExceptionHandler exceptionHandler, Exception primaryException) {
        List<Exception> exceptions = exceptionHandler.getExceptions();
        List<String> warnings = exceptionHandler.getWarnings();
        if (primaryException!=null || !exceptions.isEmpty() || !warnings.isEmpty()) {
            List<String> messages = MutableList.<String>of();
            if (primaryException!=null) messages.add(primaryException.toString());
            for (Exception e: exceptions) messages.add(e.toString());
            for (String w: warnings) messages.add(w);
            rebindMetrics.noteError(messages);
        }
    }
    
    private String findCatalogItemId(ClassLoader cl, Map<String, EntityMementoManifest> entityIdToManifest, EntityMementoManifest entityManifest) {
        if (entityManifest.getCatalogItemId() != null) {
            return entityManifest.getCatalogItemId();
        }

        if (BrooklynFeatureEnablement.isEnabled(BrooklynFeatureEnablement.FEATURE_INFER_CATALOG_ITEM_ON_REBIND)) {
            //First check if any of the parent entities has a catalogItemId set.
            EntityMementoManifest ptr = entityManifest;
            while (ptr != null) {
                if (ptr.getCatalogItemId() != null) {
                    CatalogItem<?, ?> catalogItem = CatalogUtils.getCatalogItemOptionalVersion(managementContext, ptr.getCatalogItemId());
                    if (catalogItem != null) {
                        return catalogItem.getId();
                    } else {
                        //Couldn't find a catalog item with this id, but return it anyway and
                        //let the caller deal with the error.
                        return ptr.getCatalogItemId();
                    }
                }
                if (ptr.getParent() != null) {
                    ptr = entityIdToManifest.get(ptr.getParent());
                } else {
                    ptr = null;
                }
            }

            //If no parent entity has the catalogItemId set try to match them by the type we are trying to load.
            //The current convention is to set catalog item IDs to the java type (for both plain java or CAMP plan) they represent.
            //This will be applicable only the first time the store is rebinded, while the catalog items don't have the default
            //version appended to their IDs, but then we will have catalogItemId set on entities so not neede further anyways.
            BrooklynCatalog catalog = managementContext.getCatalog();
            ptr = entityManifest;
            while (ptr != null) {
                CatalogItem<?, ?> catalogItem = catalog.getCatalogItem(ptr.getType(), BrooklynCatalog.DEFAULT_VERSION);
                if (catalogItem != null) {
                    LOG.debug("Inferred catalog item ID "+catalogItem.getId()+" for "+entityManifest+" from ancestor "+ptr);
                    return catalogItem.getId();
                }
                if (ptr.getParent() != null) {
                    ptr = entityIdToManifest.get(ptr.getParent());
                } else {
                    ptr = null;
                }
            }

            //As a last resort go through all catalog items trying to load the type and use the first that succeeds.
            //But first check if can be loaded from the default classpath
            try {
                cl.loadClass(entityManifest.getType());
                return null;
            } catch (ClassNotFoundException e) {
            }

            for (CatalogItem<?, ?> item : catalog.getCatalogItems()) {
                BrooklynClassLoadingContext loader = CatalogUtils.newClassLoadingContext(managementContext, item);
                boolean canLoadClass = loader.tryLoadClass(entityManifest.getType()).isPresent();
                if (canLoadClass) {
                    LOG.warn("Missing catalog item for "+entityManifest.getId()+", inferring as "+item.getId()+" because that is able to load the item");
                    return item.getId();
                }
            }
        }
        return null;
    }

    private class BrooklynObjectInstantiator {

        private final ClassLoader classLoader;
        private final RebindContextImpl rebindContext;
        private final Reflections reflections;
        
        private BrooklynObjectInstantiator(ClassLoader classLoader, RebindContextImpl rebindContext, Reflections reflections) {
            this.classLoader = classLoader;
            this.rebindContext = rebindContext;
            this.reflections = reflections;
        }

        private Entity newEntity(String entityId, String entityType, String catalogItemId) {
            Class<? extends Entity> entityClazz = load(Entity.class, entityType, catalogItemId, entityId);

            Entity entity;
            
            if (InternalFactory.isNewStyle(entityClazz)) {
                // Not using entityManager.createEntity(EntitySpec) because don't want init() to be called.
                // Creates an uninitialized entity, but that has correct id + proxy.
                InternalEntityFactory entityFactory = managementContext.getEntityFactory();
                entity = entityFactory.constructEntity(entityClazz, Reflections.getAllInterfaces(entityClazz), entityId);

            } else {
                LOG.warn("Deprecated rebind of entity without no-arg constructor; this may not be supported in future versions: id="+entityId+"; type="+entityType);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!

                Map<Object,Object> flags = Maps.newLinkedHashMap();
                flags.put("id", entityId);
                if (AbstractApplication.class.isAssignableFrom(entityClazz)) flags.put("mgmt", managementContext);

                // TODO document the multiple sources of flags, and the reason for setting the mgmt context *and* supplying it as the flag
                // (NB: merge reported conflict as the two things were added separately)
                entity = (Entity) invokeConstructor(null, entityClazz, new Object[] {flags}, new Object[] {flags, null}, new Object[] {null}, new Object[0]);

                // In case the constructor didn't take the Map arg, then also set it here.
                // e.g. for top-level app instances such as WebClusterDatabaseExampleApp will (often?) not have
                // interface + constructor.
                // TODO On serializing the memento, we should capture which interfaces so can recreate
                // the proxy+spec (including for apps where there's not an obvious interface).
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", entityId), entity);
                if (entity instanceof AbstractApplication) {
                    FlagUtils.setFieldsFromFlags(ImmutableMap.of("mgmt", managementContext), entity);
                }
                ((AbstractEntity)entity).setManagementContext(managementContext);
                managementContext.prePreManage(entity);
            }
            
            setCatalogItemId(entity, catalogItemId);
            return entity;
        }

        private void setCatalogItemId(BrooklynObject item, String catalogItemId) {
            if (catalogItemId!=null) {
                ((BrooklynObjectInternal)item).setCatalogItemId(catalogItemId);
            }
        }

        private <T extends BrooklynObject> Class<? extends T> load(Class<T> bType, Memento memento) {
            return load(bType, memento.getType(), memento.getCatalogItemId(), memento.getId());
        }
        @SuppressWarnings("unchecked")
        private <T extends BrooklynObject> Class<? extends T> load(Class<T> bType, String jType, String catalogItemId, String contextSuchAsId) {
            checkNotNull(jType, "Type of %s (%s) must not be null", contextSuchAsId, bType.getSimpleName());
            if (catalogItemId != null) {
                BrooklynClassLoadingContext loader = getLoadingContextFromCatalogItemId(catalogItemId, classLoader, rebindContext);
                return loader.loadClass(jType, bType);
            } else {
                // we have previously used reflections; not sure if that's needed?
                try {
                    return (Class<T>)reflections.loadClass(jType);
                } catch (Exception e) {
                    LOG.warn("Unable to load "+jType+" using reflections; will try standard context");
                }

                if (BrooklynFeatureEnablement.isEnabled(BrooklynFeatureEnablement.FEATURE_INFER_CATALOG_ITEM_ON_REBIND)) {
                    //Try loading from whichever catalog bundle succeeds.
                    BrooklynCatalog catalog = managementContext.getCatalog();
                    for (CatalogItem<?, ?> item : catalog.getCatalogItems()) {
                        BrooklynClassLoadingContext catalogLoader = CatalogUtils.newClassLoadingContext(managementContext, item);
                        Maybe<Class<?>> catalogClass = catalogLoader.tryLoadClass(jType);
                        if (catalogClass.isPresent()) {
                            return (Class<? extends T>) catalogClass.get();
                        }
                    }
                    throw new IllegalStateException("No catalogItemId specified and can't load class from either classpath of catalog items");
                } else {
                    throw new IllegalStateException("No catalogItemId specified and can't load class from classpath");
                }

            }
        }

        private BrooklynClassLoadingContext getLoadingContextFromCatalogItemId(String catalogItemId, ClassLoader classLoader, RebindContext rebindContext) {
            Preconditions.checkNotNull(catalogItemId, "catalogItemId required (should not be null)");
            CatalogItem<?, ?> catalogItem = rebindContext.lookup().lookupCatalogItem(catalogItemId);
            if (catalogItem != null) {
                return CatalogUtils.newClassLoadingContext(managementContext, catalogItem);
            } else {
                throw new IllegalStateException("Failed to load catalog item " + catalogItemId + " required for rebinding.");
            }
        }

        /**
         * Constructs a new location, passing to its constructor the location id and all of memento.getFlags().
         */
        private Location newLocation(String locationId, String locationType) {
            Class<? extends Location> locationClazz = reflections.loadClass(locationType, Location.class);

            if (InternalFactory.isNewStyle(locationClazz)) {
                // Not using loationManager.createLocation(LocationSpec) because don't want init() to be called
                // TODO Need to rationalise this to move code into methods of InternalLocationFactory.
                //      But note that we'll change all locations to be entities at some point!
                // See same code approach used in #newEntity(EntityMemento, Reflections)
                InternalLocationFactory locationFactory = managementContext.getLocationFactory();
                Location location = locationFactory.constructLocation(locationClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", locationId), location);
                managementContext.prePreManage(location);
                ((AbstractLocation)location).setManagementContext(managementContext);

                return location;
            } else {
                LOG.warn("Deprecated rebind of location without no-arg constructor; this may not be supported in future versions: id="+locationId+"; type="+locationType);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!
                Map<String,?> flags = MutableMap.of("id", locationId, "deferConstructionChecks", true);

                return (Location) invokeConstructor(reflections, locationClazz, new Object[] {flags});
            }
            // note 'used' config keys get marked in BasicLocationRebindSupport
        }

        /**
         * Constructs a new policy, passing to its constructor the policy id and all of memento.getConfig().
         */
        private Policy newPolicy(PolicyMemento memento) {
            String id = memento.getId();
            Class<? extends Policy> policyClazz = load(Policy.class, memento.getType(), memento.getCatalogItemId(), id);
            
            Policy policy;
            if (InternalFactory.isNewStyle(policyClazz)) {
                InternalPolicyFactory policyFactory = managementContext.getPolicyFactory();
                policy = policyFactory.constructPolicy(policyClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", id), policy);
                ((AbstractPolicy)policy).setManagementContext(managementContext);

            } else {
                LOG.warn("Deprecated rebind of policy without no-arg constructor; this may not be supported in future versions: id="+id+"; type="+policyClazz);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!
                Map<String, Object> flags = MutableMap.<String, Object>of(
                    "id", id, 
                    "deferConstructionChecks", true,
                    "noConstructionInit", true);
                flags.putAll(memento.getConfig());

                policy = invokeConstructor(null, policyClazz, new Object[] {flags});
            }
            
            setCatalogItemId(policy, memento.getCatalogItemId());
            return policy;
        }

        /**
         * Constructs a new enricher, passing to its constructor the enricher id and all of memento.getConfig().
         */
        private Enricher newEnricher(EnricherMemento memento) {
            Class<? extends Enricher> enricherClazz = load(Enricher.class, memento);
            String id = memento.getId();

            Enricher enricher;
            if (InternalFactory.isNewStyle(enricherClazz)) {
                InternalPolicyFactory policyFactory = managementContext.getPolicyFactory();
                enricher = policyFactory.constructEnricher(enricherClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", id), enricher);
                ((AbstractEnricher)enricher).setManagementContext(managementContext);

            } else {
                LOG.warn("Deprecated rebind of enricher without no-arg constructor; this may not be supported in future versions: id="+id+"; type="+enricherClazz);

                // There are several possibilities for the constructor; find one that works.
                // Prefer passing in the flags because required for Application to set the management context
                // TODO Feels very hacky!
                Map<String, Object> flags = MutableMap.<String, Object>of(
                    "id", id, 
                    "deferConstructionChecks", true,
                    "noConstructionInit", true);
                flags.putAll(memento.getConfig());

                enricher = invokeConstructor(reflections, enricherClazz, new Object[] {flags});
            }
            
            setCatalogItemId(enricher, memento.getCatalogItemId());
            return enricher;
        }

        /**
         * Constructs a new enricher, passing to its constructor the enricher id and all of memento.getConfig().
         */
        private Feed newFeed(FeedMemento memento) {
            Class<? extends Feed> feedClazz = load(Feed.class, memento);
            String id = memento.getId();

            Feed feed;
            if (InternalFactory.isNewStyle(feedClazz)) {
                InternalPolicyFactory policyFactory = managementContext.getPolicyFactory();
                feed = policyFactory.constructFeed(feedClazz);
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", id), feed);
                ((AbstractFeed)feed).setManagementContext(managementContext);

            } else {
                throw new IllegalStateException("rebind of feed without no-arg constructor unsupported: id="+id+"; type="+feedClazz);
            }
            
            setCatalogItemId(feed, memento.getCatalogItemId());
            return feed;
        }

        @SuppressWarnings({ "rawtypes" })
        private CatalogItem<?, ?> newCatalogItem(CatalogItemMemento memento) {
            String id = memento.getId();
            // catalog item subtypes are internal to brooklyn, not in osgi
            String itemType = checkNotNull(memento.getType(), "catalog item type of %s must not be null in memento", id);
            Class<? extends CatalogItem> clazz = reflections.loadClass(itemType, CatalogItem.class);
            return invokeConstructor(reflections, clazz, new Object[]{});
        }

        private <T> T invokeConstructor(Reflections reflections, Class<T> clazz, Object[]... possibleArgs) {
            for (Object[] args : possibleArgs) {
                try {
                    Optional<T> v = Reflections.invokeConstructorWithArgs(clazz, args, true);
                    if (v.isPresent()) {
                        return v.get();
                    }
                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                }
            }
            throw new IllegalStateException("Cannot instantiate instance of type "+clazz+"; expected constructor signature not found");
        }
    }

    protected BrooklynMementoPersister getPersister() {
        return rebindManager.getPersister();
    }
    
    protected <T extends TreeNode> Map<String, T> sortParentFirst(Map<String, T> nodes) {
        return RebindManagerImpl.sortParentFirst(nodes);
    }

    /** logs at debug, except during subsequent read-only rebinds, in which it logs trace */
    private void logRebindingDebug(String message, Object... args) {
        if (shouldLogRebinding()) {
            LOG.debug(message, args);
        } else {
            LOG.trace(message, args);
        }
    }
    
    protected boolean shouldLogRebinding() {
        return (readOnlyRebindCount.get() < 5) || (readOnlyRebindCount.get()%1000==0);
    }

}