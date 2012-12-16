/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.scr.impl.manager;


import java.security.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.felix.scr.Component;
import org.apache.felix.scr.Reference;
import org.apache.felix.scr.impl.BundleComponentActivator;
import org.apache.felix.scr.impl.helper.BindMethods;
import org.apache.felix.scr.impl.helper.MethodResult;
import org.apache.felix.scr.impl.metadata.ReferenceMetadata;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServicePermission;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.log.LogService;


/**
 * The <code>DependencyManager</code> manages the references to services
 * declared by a single <code>&lt;reference&gt;</code element in component
 * descriptor.
 */
public class DependencyManager<S, T> implements Reference
{
    // mask of states ok to send events
    private static final int STATE_MASK = //Component.STATE_UNSATISFIED |
         Component.STATE_ACTIVE | Component.STATE_REGISTERED | Component.STATE_FACTORY;

    // the component to which this dependency belongs
    private final AbstractComponentManager<S> m_componentManager;

    // Reference to the metadata
    private final ReferenceMetadata m_dependencyMetadata;

    private final AtomicReference<ServiceTracker<T, RefPair<T>>> trackerRef = new AtomicReference<ServiceTracker<T, RefPair<T>>>();

    private final AtomicReference<Customizer<T>> customizerRef = new AtomicReference<Customizer<T>>();

    private BindMethods m_bindMethods;

    // the target service filter string
    private volatile String m_target;

    // the target service filter
    private volatile Filter m_targetFilter;

    private boolean registered;


    /**
     * Constructor that receives several parameters.
     *
     * @param dependency An object that contains data about the dependency
     */
    DependencyManager( AbstractComponentManager<S> componentManager, ReferenceMetadata dependency )
    {
        m_componentManager = componentManager;
        m_dependencyMetadata = dependency;

        // dump the reference information if DEBUG is enabled
        if ( m_componentManager.isLogEnabled( LogService.LOG_DEBUG ) )
        {
            m_componentManager
                .log(
                    LogService.LOG_DEBUG,
                    "Dependency Manager {0} created: interface={1}, filter={2}, policy={3}, cardinality={4}, bind={5}, unbind={6}",
                    new Object[]
                        { getName(), dependency.getInterface(), dependency.getTarget(), dependency.getPolicy(),
                            dependency.getCardinality(), dependency.getBind(), dependency.getUnbind() }, null );
        }
    }

    /**
     * Initialize binding methods.
     */
    void initBindingMethods(BindMethods bindMethods)
    {
       m_bindMethods = bindMethods;
    }

    private interface Customizer<T> extends ServiceTrackerCustomizer<T, RefPair<T>>
    {
        boolean open(ServiceTracker<T, RefPair<T>> tracker);

        void close(ServiceTracker<T, RefPair<T>> tracker);

        Collection<RefPair<T>> getRefs( ServiceTracker<T, RefPair<T>> tracker );
    }




    private class FactoryCustomizer implements Customizer<T> {

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( !isOptional() )
            {
                m_componentManager.activateInternal();
            }
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( !isOptional() )
            {
                ServiceTracker<T, RefPair<T>> tracker = trackerRef.get();
                if (tracker != null && tracker.isEmpty())
                {
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                }
            }
        }

        public boolean open( ServiceTracker<T, RefPair<T>> tracker )
        {
            boolean success = m_dependencyMetadata.isOptional() || !tracker.isEmpty();
            tracker.getTracked( true );   //TODO activate method??
            return success;
        }

        public void close( ServiceTracker<T, RefPair<T>> tracker )
        {
//            for ( RefPair<T> ref: getRefs( tracker ))
//            {
//                if ( ref.getServiceObject() != null)
//                {
//                    ref.setServiceObject( null );
//                    m_componentManager.getActivator().getBundleContext().ungetService( ref.getRef() );
//                }
//            }
            if (tracker != null)
            {
                  tracker.deactivate();
            }
        }

        public Collection<RefPair<T>> getRefs( ServiceTracker<T, RefPair<T>> tracker )
        {
            return Collections.emptyList();
        }
    }

    private class MultipleDynamicCustomizer implements Customizer<T> {

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            if (isActive())
            {
                 m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext());
            }
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (isActive())
            {
                m_componentManager.invokeBindMethod( DependencyManager.this, refPair );
            }
            else if ( !isOptional() )
            {
                m_componentManager.activateInternal();
            }
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair );
            }
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( isActive() )
            {
                boolean unbind = true;
                if ( !isOptional() )
                {
                    ServiceTracker tracker = trackerRef.get();
                    if (tracker.isEmpty())
                    {
                        unbind = false;
                    }
                }
                if ( unbind )
                {
                    m_componentManager.invokeUnbindMethod( DependencyManager.this, refPair );
                }
                else
                {
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                }
            }
            if (refPair.getServiceObject() != null)
            {
                m_componentManager.getActivator().getBundleContext().ungetService( serviceReference );
            }
        }

        public boolean open( ServiceTracker<T, RefPair<T>> tracker )
        {
            boolean success = m_dependencyMetadata.isOptional();
            SortedMap<ServiceReference<T>, RefPair<T>> tracked = tracker.getTracked( true );
            for (RefPair<T> refPair: tracked.values())
            {
                synchronized (refPair)
                {
                    success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext());
                }
            }
            return success;
        }

        public void close( ServiceTracker<T, RefPair<T>> tracker )
        {
            if (tracker != null)
            {
                for ( RefPair<T> ref: getRefs( tracker ))
                {
                    if ( ref.getServiceObject() != null)
                    {
                        ref.setServiceObject( null );
                        m_componentManager.getActivator().getBundleContext().ungetService( ref.getRef() );
                    }
                }
                tracker.deactivate();
            }
        }

        public Collection<RefPair<T>> getRefs( ServiceTracker<T, RefPair<T>> tracker )
        {
            return tracker.getTracked( true ).values();
        }
    }

    private class MultipleStaticGreedyCustomizer implements Customizer<T> {


        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            if (isActive())
            {
                 m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext());
            }
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (isActive())
            {
                m_componentManager.log( LogService.LOG_DEBUG,
                    "Dependency Manager: Static dependency on {0}/{1} is broken", new Object[]
                        { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface() }, null );
                m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );

            }
            m_componentManager.activateInternal();
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair );
            }
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( isActive() )
            {
                    m_componentManager.log( LogService.LOG_DEBUG,
                        "Dependency Manager: Static dependency on {0}/{1} is broken", new Object[]
                            { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface() }, null );
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                    m_componentManager.activateInternal();

            }
        }

        public boolean open( ServiceTracker<T, RefPair<T>> tracker )
        {
            boolean success = m_dependencyMetadata.isOptional();
            SortedMap<ServiceReference<T>, RefPair<T>> tracked = tracker.getTracked( success || !tracker.isEmpty() );
            for (RefPair<T> refPair: tracked.values())
            {
                synchronized (refPair)
                {
                    success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext());
                }
            }
            return success;
        }

        public void close( ServiceTracker<T, RefPair<T>> tracker )
        {
            for ( RefPair<T> ref: getRefs( tracker ))
            {
                if ( ref.getServiceObject() != null)
                {
                    ref.setServiceObject( null );
                    m_componentManager.getActivator().getBundleContext().ungetService( ref.getRef() );
                }
            }
            if (tracker != null)
            {
                  tracker.deactivate();
            }
        }

        public Collection<RefPair<T>> getRefs( ServiceTracker<T, RefPair<T>> tracker )
        {
            return tracker.getTracked( null ).values();
        }
    }

    private class MultipleStaticReluctantCustomizer implements Customizer<T> {

        private final Collection<RefPair<T>> refs = new ArrayList<RefPair<T>>();

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (!isActive())
            {
                m_componentManager.activateInternal();
            }
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair );
            }
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( isActive() )
            {
                if (refs.contains( refPair ))
                {
                    m_componentManager.log( LogService.LOG_DEBUG,
                        "Dependency Manager: Static dependency on {0}/{1} is broken", new Object[]
                            { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface() }, null );
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );

                    // FELIX-2368: immediately try to reactivate
                    m_componentManager.activateInternal();

                }
            }
            if (refPair.getServiceObject() != null)
            {
                m_componentManager.getActivator().getBundleContext().ungetService( serviceReference );
            }
        }

        public boolean open( ServiceTracker<T, RefPair<T>> tracker )
        {
            boolean success = m_dependencyMetadata.isOptional();
            SortedMap<ServiceReference<T>, RefPair<T>> tracked = tracker.getTracked( true );
            for (RefPair<T> refPair: tracked.values())
            {
                synchronized (refPair)
                {
                    success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext());
                }
                refs.add(refPair) ;
            }
            return success;
        }

        public void close( ServiceTracker<T, RefPair<T>> tracker )
        {
            for ( RefPair<T> ref: getRefs( tracker ))
            {
                if ( ref.getServiceObject() != null)
                {
                    ref.setServiceObject( null );
                    m_componentManager.getActivator().getBundleContext().ungetService( ref.getRef() );
                }
            }
            refs.clear();
            if (tracker != null)
            {
                  tracker.deactivate();
            }
        }

        public Collection<RefPair<T>> getRefs( ServiceTracker<T, RefPair<T>> tracker )
        {
            return refs;
        }
    }

    private class SingleDynamicCustomizer implements Customizer<T> {

        private RefPair<T> refPair;

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (isActive() )
            {
                if ( this.refPair == null || ( !isReluctant() && refPair.getRef().compareTo( this.refPair.getRef() ) > 0 ) )
                {
                    synchronized ( refPair )
                    {
                        if (!m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() ))
                        {
                            //TODO error???
                        }
                    }
                    m_componentManager.invokeBindMethod( DependencyManager.this, refPair );
                    if ( this.refPair != null )
                    {
                        m_componentManager.invokeUnbindMethod( DependencyManager.this, this.refPair );
                        closeRefPair();
                    }
                    this.refPair = refPair;
                }
            }
            else if ( !isOptional() )
            {
                m_componentManager.activateInternal();
            }
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair );
            }
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if (refPair == this.refPair)
            {
                if ( isActive() )
                {
                    RefPair<T> nextRefPair = null;
                    ServiceTracker<T, RefPair<T>> tracker = trackerRef.get();
                    if ( !tracker.isEmpty() )
                    {
                        SortedMap<ServiceReference<T>, RefPair<T>> tracked = tracker.getTracked( true );
                        nextRefPair = tracked.values().iterator().next();
                        synchronized ( nextRefPair )
                        {
                            if (!m_bindMethods.getBind().getServiceObject( nextRefPair, m_componentManager.getActivator().getBundleContext() ))
                            {
                                //TODO error???
                            }
                        }
                        m_componentManager.invokeBindMethod( DependencyManager.this, nextRefPair );
                    }

                    if ( isOptional() || nextRefPair != null)
                    {
                        m_componentManager.invokeUnbindMethod( DependencyManager.this, refPair );
                        closeRefPair();
                        this.refPair = nextRefPair;
                    }
                    else //required and no replacement service, deactivate
                    {
                        m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                    }
                }
            }
        }

        public boolean open( ServiceTracker<T, RefPair<T>> tracker )
        {
            boolean success = m_dependencyMetadata.isOptional();
            if ( success || !tracker.isEmpty() )
            {
                SortedMap<ServiceReference<T>, RefPair<T>> tracked = tracker.getTracked( true );
                if ( !tracked.isEmpty() )
                {
                    RefPair<T> refPair = tracked.values().iterator().next();
                    synchronized ( refPair )
                    {
                        success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() );
                    }
                    this.refPair = refPair;
                }
            }
            return success;
        }

        public void close( ServiceTracker<T, RefPair<T>> tracker )
        {
            closeRefPair();
            if (tracker != null)
            {
                  tracker.deactivate();
            }
        }

        private void closeRefPair()
        {
            if ( refPair != null && refPair.getServiceObject() != null )
            {
                refPair.setServiceObject( null );
                m_componentManager.getActivator().getBundleContext().ungetService( refPair.getRef() );
            }
            refPair = null;
        }

        public Collection<RefPair<T>> getRefs( ServiceTracker<T, RefPair<T>> tracker )
        {
            return refPair == null? Collections.<RefPair<T>>emptyList(): Collections.singleton( refPair );
        }
    }

    private class SingleStaticCustomizer implements Customizer<T>
    {

        private RefPair<T> refPair;

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference );
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( isActive() )
            {
                if ( !isReluctant() && ( this.refPair == null || refPair.getRef().compareTo( this.refPair.getRef() ) > 0 ) )
                {
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                    m_componentManager.activateInternal();
                }
            }
            else
            {
                m_componentManager.activateInternal();
            }
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( isActive() )
            {
                m_componentManager.update( DependencyManager.this, refPair );
            }
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair )
        {
            if ( isActive() && refPair == this.refPair )
            {
                m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                m_componentManager.activateInternal();
            }
        }

        public boolean open( ServiceTracker<T, RefPair<T>> tracker )
        {
            boolean success = m_dependencyMetadata.isOptional();
            if ( success || !tracker.isEmpty() )
            {
                SortedMap<ServiceReference<T>, RefPair<T>> tracked = tracker.getTracked( true );
                if ( !tracked.isEmpty() )
                {
                    RefPair<T> refPair = tracked.values().iterator().next();
                    synchronized ( refPair )
                    {
                        success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() );
                    }
                    this.refPair = refPair;
                }
            }
            return success;
        }

        public void close( ServiceTracker<T, RefPair<T>> tracker )
        {
            if ( refPair != null && refPair.getServiceObject() != null )
            {
                refPair.setServiceObject( null );
                m_componentManager.getActivator().getBundleContext().ungetService( refPair.getRef() );
            }
            refPair = null;
            if ( tracker != null )
            {
                tracker.deactivate();
            }
        }

        public Collection<RefPair<T>> getRefs( ServiceTracker<T, RefPair<T>> tracker )
        {
            return refPair == null ? Collections.<RefPair<T>>emptyList() : Collections.singleton( refPair );
        }
    }

    private boolean isActive()
    {
        ServiceTracker<T, RefPair<T>> tracker = trackerRef.get();
        return tracker != null && tracker.isActive();
    }

    //---------- ServiceListener interface ------------------------------------

    /**
     * Called when a registered service changes state. In the case of service
     * modification the service is assumed to be removed and added again.
     */
    /*
    public void serviceChanged( ServiceEvent event )
    {
        final ServiceReference<T> ref = ( ServiceReference<T> ) event.getServiceReference();
        final String serviceString = "Service " + m_dependencyMetadata.getInterface() + "/"
            + ref.getProperty( Constants.SERVICE_ID );
        Collection<ServiceReference<T>> changes = null;
        try
        {
            switch ( event.getType() )
            {
                case ServiceEvent.REGISTERED:
                    m_componentManager.log( LogService.LOG_DEBUG, "Dependency Manager: Adding {0}", new Object[]
                        { serviceString }, null );

                    // consider the service if the filter matches
                    if ( targetFilterMatch( ref ) )
                    {
                        synchronized ( added )
                        {
                            added.add( ref );
                        }
                        synchronized (enableLock)
                        {
                            //wait for enable to complete
                        }
                        synchronized ( added )
                        {
                            if (!added.contains( ref ))
                            {
                                break;
                            }
                        }
                        m_size.incrementAndGet();
                        changes = added;
                        serviceAdded( ref );
                    }
                    else
                    {
                        m_componentManager.log( LogService.LOG_DEBUG,
                            "Dependency Manager: Ignoring added Service for {0} : does not match target filter {1}",
                            new Object[]
                                { m_dependencyMetadata.getName(), getTarget() }, null );
                    }
                    break;

                case ServiceEvent.MODIFIED:
                    m_componentManager.log( LogService.LOG_DEBUG, "Dependency Manager: Updating {0}", new Object[]
                        { serviceString }, null );

                    if ( getBoundService( ref ) == null )
                    {
                        // service not currently bound --- what to do ?
                        // if static
                        //    if inactive and target match: activate
                        // if dynamic or greedy
                        //    if multiple and target match: bind
                        if ( targetFilterMatch( ref ) )
                        {
                            synchronized ( added )
                            {
                                added.add( ref );
                            }
                            synchronized (enableLock)
                            {
                                //wait for enable to complete
                            }
                            synchronized ( added )
                            {
                                if (!added.contains( ref ))
                                {
                                    break;
                                }
                            }
                            changes = added;
                            m_size.incrementAndGet();
                                if ( isStatic() )
                                {
                                    // if static reference: activate if currentl unsatisifed, otherwise no influence
                                    if ( m_componentManager.getState() == AbstractComponentManager.STATE_UNSATISFIED )
                                    {
                                        m_componentManager.log( LogService.LOG_DEBUG,
                                            "Dependency Manager: Service {0} registered, activate component", new Object[]
                                                { m_dependencyMetadata.getName() }, null );

                                        // immediately try to activate the component (FELIX-2368)
                                        m_componentManager.activateInternal();
                                    }
                                }
                                else if ( isMultiple() || !isReluctant())
                                {
                                    // if dynamic and multiple reference, bind, otherwise ignore
                                    serviceAdded( ref );
                                }
                            }

                    }
                    else if ( !targetFilterMatch( ref ) )
                    {
                        synchronized ( removed )
                        {
                            removed.add( ref );
                        }
                        synchronized (enableLock)
                        {
                            //wait for enable to complete
                        }
                        synchronized ( removed )
                        {
                            if (!removed.contains( ref ))
                            {
                                break;
                            }
                        }
                        changes = removed;
                        m_size.set( getServiceReferenceCount() );
                        serviceRemoved( ref );
                    }
                    else
                    {
                        // update the service binding due to the new properties
                        m_componentManager.update( this, ref );
                    }

                    break;

                case ServiceEvent.UNREGISTERING:
                    m_componentManager.log( LogService.LOG_DEBUG, "Dependency Manager: Removing {0}", new Object[]
                        { serviceString }, null );

                    // manage the service counter if the filter matchs
                    if ( targetFilterMatch( ref ) )
                    {
                        synchronized ( removed )
                        {
                            removed.add( ref );
                        }
                        synchronized (enableLock)
                        {
                            //wait for enable to complete
                        }
                        synchronized ( removed )
                        {
                            if (!removed.contains( ref ))
                            {
                                break;
                            }
                        }
                        changes = removed;
                        m_size.set( getServiceReferenceCount() );
                        serviceRemoved( ref );
                    }
                    else
                    {
                        m_componentManager
                            .log(
                                LogService.LOG_DEBUG,
                                "Dependency Manager: Not counting Service for {0} : Service {1} does not match target filter {2}",
                                new Object[]
                                    { m_dependencyMetadata.getName(), ref.getProperty( Constants.SERVICE_ID ), getTarget() },
                                null );
                        // remove the service ignoring the filter match because if the
                        // service is bound, it has to be removed no matter what
                        serviceRemoved( ref );
                    }


                    break;
            }
        }
        finally
        {
            if ( changes != null)
            {
                synchronized ( changes )
                {
                    changes.remove( ref );
                    changes.notify();
                }
            }
        }
    }
 *?

    /**
     * Called by the {@link #serviceChanged(ServiceEvent)} method if a new
     * service is registered with the system or if a registered service has been
     * modified.
     * <p>
     * Depending on the component state and dependency configuration, the
     * component may be activated, re-activated or the service just be provided.
     *
     * See Compendium 4.3 table 112.1
     *
     * @param reference The reference to the service newly registered or
     *      modified.
     */
    /*
    private void serviceAdded( ServiceReference<T> reference )
    {
        // if the component is currently unsatisfied, it may become satisfied
        // by adding this service, try to activate (also schedule activation
        // if the component is pending deactivation)
        if ( m_componentManager.getState() == AbstractComponentManager.STATE_UNSATISFIED && !isOptional() )
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                "Dependency Manager: Service {0} registered, activate component", new Object[]
                    { m_dependencyMetadata.getName() }, null );

            // immediately try to activate the component (FELIX-2368)
            boolean handled = m_componentManager.activateInternal();
            if (!handled)
            {
                m_componentManager.log( LogService.LOG_DEBUG,
                    "Dependency Manager: Service {0} activation did not occur on this thread", new Object[]
                        { m_dependencyMetadata.getName() }, null );

                Map<DependencyManager<S, ?>, Map<ServiceReference<?>, RefPair<?>>> dependenciesMap = m_componentManager.getDependencyMap();
                if (dependenciesMap  != null) {
                    //someone else has managed to activate
                    Map<ServiceReference<T>, RefPair<T>> references = (Map)dependenciesMap.get( this );
                    if (references == null )
                    {
                        throw new IllegalStateException( "Allegedly active but dependency manager not represented: " + this );
                    }
                    handled = references.containsKey( reference );
                }
            }
            if (handled)
            {
                m_componentManager.log( LogService.LOG_DEBUG,
                    "Dependency Manager: Service {0} activation on other thread bound service reference {1}", new Object[]
                        { m_dependencyMetadata.getName(), reference }, null );
                return;
            }
            //release our read lock and wait for activation to complete
//            m_componentManager.releaseReadLock( "DependencyManager.serviceAdded.nothandled.1" );
//            m_componentManager.obtainReadLock( "DependencyManager.serviceAdded.nothandled.2" );
            m_componentManager.log( LogService.LOG_DEBUG,
                    "Dependency Manager: Service {0} activation on other thread: after releasing lock, component instance is: {1}", new Object[]
                    {m_dependencyMetadata.getName(), m_componentManager.getInstance()}, null );
        }

        // otherwise check whether the component is in a state to handle the event
        if ( handleServiceEvent() )
        {

            // FELIX-1413: if the dependency is static and reluctant and the component is
            // satisfied (active) added services are not considered until
            // the component is reactivated for other reasons.
            if ( m_dependencyMetadata.isStatic() )
            {
                if ( m_dependencyMetadata.isReluctant() )
                {
                    m_componentManager.log( LogService.LOG_DEBUG,
                            "Dependency Manager: Added service {0} is ignored for static reluctant reference", new Object[]
                            {m_dependencyMetadata.getName()}, null );
                }
                else
                {
                    Map<DependencyManager<S, ?>, Map<ServiceReference<?>, RefPair<?>>> dependenciesMap = m_componentManager.getDependencyMap();
                    if ( dependenciesMap != null )
                    {
                        Map<ServiceReference<T>, RefPair<T>> bound = (Map)dependenciesMap.get( this );
                        if ( m_dependencyMetadata.isMultiple() ||
                                                bound.isEmpty() ||
                                                reference.compareTo( bound.keySet().iterator().next() ) > 0 )
                                        {
                                            m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                                            m_componentManager.activateInternal();
                                        }
                    }
                }
            }

            // otherwise bind if we have a bind method and the service needs
            // be bound
            else if ( m_dependencyMetadata.getBind() != null )
            {
                // multiple bindings or not bound at all yet
                if ( m_dependencyMetadata.isMultiple() || !isBound() )
                {
                    // bind the service, getting it if required
                    m_componentManager.invokeBindMethod( this, reference );
                }
                else if ( !isReluctant() )
                {
                    //dynamic greedy single: bind then unbind
                    Map<DependencyManager<S, ?>, Map<ServiceReference<?>, RefPair<?>>> dependenciesMap = m_componentManager.getDependencyMap();
                    if ( dependenciesMap != null )
                    {
                        Map<ServiceReference<T>, RefPair<T>> bound = (Map)dependenciesMap.get( this );
                        ServiceReference oldRef = bound.keySet().iterator().next();
                        if ( reference.compareTo( oldRef ) > 0 )
                        {
                            m_componentManager.invokeBindMethod( this, reference );
                            m_componentManager.invokeUnbindMethod( this, oldRef );
                        }
                    }
                }
            }
        }

        else
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                "Dependency Manager: Ignoring service addition, wrong state {0}", new Object[]
                    { m_componentManager.state() }, null );
        }
    }

   *?
    /**
     * Called by the {@link #serviceChanged(ServiceEvent)} method if an existing
     * service is unregistered from the system or if a registered service has
     * been modified.
     * <p>
     * Depending on the component state and dependency configuration, the
     * component may be deactivated, re-activated, the service just be unbound
     * with or without a replacement service.
     *
     * @param reference The reference to the service unregistering or being
     *      modified.
     */
    /*
    private void serviceRemoved( ServiceReference<T> reference )
    {
        // if the dependency is not satisfied anymore, we have to
        // deactivate the component
        if ( !isSatisfied() )
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                "Dependency Manager: Deactivating component due to mandatory dependency on {0}/{1} not satisfied",
                new Object[]
                    { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface() }, null );

            // deactivate the component now
            m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
        }

        // check whether we are bound to that service, do nothing if not
        if ( getBoundService( reference ) == null )
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                "Dependency Manager: Ignoring removed Service for {0} : Service {1} not bound", new Object[]
                    { m_dependencyMetadata.getName(), reference.getProperty( Constants.SERVICE_ID ) }, null );
        }

        // otherwise check whether the component is in a state to handle the event
        else if ( handleServiceEvent() || (m_componentManager.getState() & (Component.STATE_DISABLED | Component.STATE_DISPOSED)) != 0 )
        {
            Map<DependencyManager<S, ?>, Map<ServiceReference<?>, RefPair<?>>> dependencyMap = m_componentManager.getDependencyMap();
            Map<ServiceReference<T>, RefPair<T>> referenceMap = null;
            if (dependencyMap != null)
            {
                referenceMap = (Map)dependencyMap.get( this );
            }
            // if the dependency is static, we have to deactivate the component
            // to "remove" the dependency
            if ( m_dependencyMetadata.isStatic() )
            {
                try
                {
                    m_componentManager.log( LogService.LOG_DEBUG,
                        "Dependency Manager: Static dependency on {0}/{1} is broken", new Object[]
                            { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface() }, null );
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                    if ( referenceMap != null )
                    {
                        referenceMap.remove( reference );
                    }

                    // FELIX-2368: immediately try to reactivate
                    m_componentManager.activateInternal();
                }
                catch ( Exception ex )
                {
                    m_componentManager.log( LogService.LOG_ERROR, "Exception while recreating dependency ", ex );
                }
            }

            // dynamic dependency, multiple or single but this service is the bound one
            else
            {

                // try to bind a replacement service first if this is a unary
                // cardinality reference and a replacement is available.
                if ( !m_dependencyMetadata.isMultiple() )
                {
                    // if the dependency is mandatory and no replacement is
                    // available, bind returns false and we deactivate
                    // bind best matching service
                    ServiceReference<T> ref = getFrameworkServiceReference();

                    if ( ref == null )
                    {
                        if ( !m_dependencyMetadata.isOptional() )
                        {
                            m_componentManager
                                .log(
                                        LogService.LOG_DEBUG,
                                        "Dependency Manager: Deactivating component due to mandatory dependency on {0}/{1} not satisfied",
                                        new Object[]
                                                {m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface()}, null );
                            m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false );
                        }
                    }
                    else
                    {
                        m_componentManager.invokeBindMethod( this, ref );
                    }
                }

                // call the unbind method if one is defined
                if ( m_dependencyMetadata.getUnbind() != null )
                {
                    m_componentManager.invokeUnbindMethod( this, reference );
                }

                // make sure the service is returned
                ungetService( reference );
                //service is no longer available, don't track it any longer.
                if ( referenceMap != null )
                {
                    referenceMap.remove( reference );
                }
            }
        }

        else
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                "Dependency Manager: Ignoring service removal, wrong state {0}", new Object[]
                    { m_componentManager.state() }, null );
        }
    }
   */

    private boolean handleServiceEvent()
    {
        return ( m_componentManager.getState() & STATE_MASK ) != 0;
    }


    //---------- Reference interface ------------------------------------------

    public String getServiceName()
    {
        return m_dependencyMetadata.getInterface();
    }


    public ServiceReference[] getServiceReferences()
    {
        return getBoundServiceReferences();
    }


    public boolean isOptional()
    {
        return m_dependencyMetadata.isOptional();
    }


    public boolean isMultiple()
    {
        return m_dependencyMetadata.isMultiple();
    }


    public boolean isStatic()
    {
        return m_dependencyMetadata.isStatic();
    }

    public boolean isReluctant()
    {
        return m_dependencyMetadata.isReluctant();
    }

    public String getBindMethodName()
    {
        return m_dependencyMetadata.getBind();
    }


    public String getUnbindMethodName()
    {
        return m_dependencyMetadata.getUnbind();
    }


    public String getUpdatedMethodName()
    {
        return m_dependencyMetadata.getUpdated();
    }


    //---------- Service tracking support -------------------------------------

    /**
     * Enables this dependency manager by starting to listen for service
     * events.
     * @throws InvalidSyntaxException if the target filter is invalid
     */
    void enable() throws InvalidSyntaxException
    {
        if ( hasGetPermission() )
        {
            // setup the target filter from component descriptor
            setTargetFilter( m_dependencyMetadata.getTarget() );

            m_componentManager.log( LogService.LOG_DEBUG,
                    "Registered for service events, currently {0} service(s) match the filter", new Object[]
                    {new Integer( size() )}, null );
        }
        else
        {
            // no services available
            m_componentManager.log( LogService.LOG_DEBUG,
                    "Not registered for service events since the bundle has no permission to get service {0}", new Object[]
                    {m_dependencyMetadata.getInterface()}, null );
        }
    }


    void deactivate()
    {
        customizerRef.get().close( trackerRef.get() );
    }


    /**
     * Returns the number of services currently registered in the system,
     * which match the service criteria (interface and optional target filter)
     * configured for this dependency. The number returned by this method has
     * no correlation to the number of services bound to this dependency
     * manager. It is actually the maximum number of services which may be
     * bound to this dependency manager.
     *
     * @see #isSatisfied()
     */
    int size()
    {
        return trackerRef.get().getTracked( null ).size();
    }


    /**
     * Returns an array of <code>ServiceReference</code> instances for services
     * implementing the interface and complying to the (optional) target filter
     * declared for this dependency. If no matching service can be found
     * <code>null</code> is returned. If the configured target filter is
     * syntactically incorrect an error message is logged with the LogService
     * and <code>null</code> is returned.
     * <p>
     * This method always directly accesses the framework's service registry
     * and ignores the services bound by this dependency manager.
     */
    ServiceReference<T>[] getFrameworkServiceReferences()
    {
        return getFrameworkServiceReferences( getTarget() );
    }


    private ServiceReference<T>[] getFrameworkServiceReferences( String targetFilter )
    {
        if ( hasGetPermission() )
        {
            // component activator may be null if disposed concurrently
            BundleComponentActivator bca = m_componentManager.getActivator();
            if ( bca == null )
            {
                return null;
            }

            // get bundle context, may be null if component deactivated since getting bca
            BundleContext bc = bca.getBundleContext();
            if ( bc == null )
            {
                return null;
            }

            try
            {
                return ( ServiceReference<T>[] ) bc.getServiceReferences(
                    m_dependencyMetadata.getInterface(), targetFilter );
            }
            catch ( IllegalStateException ise )
            {
                // bundle context is not valid any longer, cannot log
            }
            catch ( InvalidSyntaxException ise )
            {
                m_componentManager.log( LogService.LOG_ERROR, "Unexpected problem with filter ''{0}''", new Object[]
                    { targetFilter }, ise );
                return null;
            }
        }

        m_componentManager.log( LogService.LOG_DEBUG, "No permission to access the services", null );
        return null;
    }

    private int getServiceReferenceCount()
    {
        ServiceReference<T>[] refs = getFrameworkServiceReferences();
        return refs == null? 0: refs.length;
    }


    /**
     * Returns a <code>ServiceReference</code> instances for a service
     * implementing the interface and complying to the (optional) target filter
     * declared for this dependency. If no matching service can be found
     * <code>null</code> is returned. If the configured target filter is
     * syntactically incorrect an error message is logged with the LogService
     * and <code>null</code> is returned. If multiple matching services are
     * registered the service with the highest service.ranking value is
     * returned. If multiple matching services have the same service.ranking
     * value, the service with the lowest service.id is returned.
     * <p>
     * This method always directly accesses the framework's service registry
     * and ignores the services bound by this dependency manager.
     */
    ServiceReference<T> getFrameworkServiceReference()
    {
        // get the framework registered services and short cut
        ServiceReference<T>[] refs = getFrameworkServiceReferences();
        if ( refs == null )
        {
            return null;
        }
        else if ( refs.length == 1 )
        {
            return refs[0];
        }


        // find the service with the highest ranking
        ServiceReference<T> selectedRef = refs[0];
        for ( int i = 1; i < refs.length; i++ )
        {
            ServiceReference<T> ref = refs[i];
            if ( ref.compareTo( selectedRef ) > 0 )
            {
                selectedRef = ref;
            }
        }

        return selectedRef;
    }


    /**
     * Returns the service instance for the service reference returned by the
     * {@link #getFrameworkServiceReference()} method. If this returns a
     * non-<code>null</code> service instance the service is then considered
     * bound to this instance.
     */
    T getService()
    {
        ServiceReference<T> sr = getFrameworkServiceReference();
        return ( sr != null ) ? getService( sr ) : null;
    }


    /**
     * Returns an array of service instances for the service references returned
     * by the {@link #getFrameworkServiceReferences()} method. If no services
     * match the criteria configured for this dependency <code>null</code> is
     * returned. All services returned by this method will be considered bound
     * after this method returns.
     */
    T[] getServices()
    {
        ServiceReference<T>[] sr = getFrameworkServiceReferences();
        if ( sr == null || sr.length == 0 )
        {
            return null;
        }

        List<Object> services = new ArrayList<Object>();
        for ( ServiceReference<T> ref: sr )
        {
            Object service = getService( ref );
            if ( service != null )
            {
                services.add( service );
            }
        }

        return ( services.size() > 0 ) ? services.toArray((T[])new Object[services.size()]) : null;
    }


    //---------- bound services maintenance -----------------------------------

    /**
     * Returns an array of <code>ServiceReference</code> instances of all
     * services this instance is bound to or <code>null</code> if no services
     * are actually bound.
     */
    public ServiceReference<T>[] getBoundServiceReferences()
    {
        Collection<RefPair<T>> bound = customizerRef.get().getRefs( trackerRef.get() );
        if ( bound.isEmpty() )
        {
            return null;
        }
        ServiceReference<T>[] result = new ServiceReference[bound.size()];
        int i = 0;
        for (RefPair<T> ref: bound)
        {
            result[i++] = ref.getRef();
        }
        return result;
    }


    /**
     * Returns <code>true</code> if at least one service has been bound
     */
    private boolean isBound()
    {
        return !customizerRef.get().getRefs( trackerRef.get() ).isEmpty();
    }


    /**
     * Returns the RefPair containing the given service reference and the bound service
     * or <code>null</code> if this is instance is not currently bound to that
     * service.
     *
     * @param serviceReference The reference to the bound service
     *
     * @return RefPair the reference and service for the reference
     *      if the service is bound or <code>null</code> if the service is not
     *      bound.
     */
    private RefPair<T> getBoundService( ServiceReference<T> serviceReference )
    {
        return trackerRef.get().getTracked( null ).get( serviceReference );
    }


    /**
     * Returns the service described by the ServiceReference. If this instance
     * is already bound the given service, that bound service instance is
     * returned. Otherwise the service retrieved from the service registry
     * and kept as a bound service for future use.
     *
     * @param serviceReference The reference to the service to be returned
     *
     * @return The requested service or <code>null</code> if no service is
     *      registered for the service reference (any more).
     */
    T getService( ServiceReference<T> serviceReference )
    {
        // check whether we already have the service and return that one
        RefPair refPair = getBoundService( serviceReference );
        if (refPair == null)
        {
            //we don't know about this reference
            return null;
        }
        if ( refPair.getServiceObject() != null )
        {
            return (T)refPair.getServiceObject();
        }
        T serviceObject;
        // otherwise acquire the service
        try
        {
            serviceObject = m_componentManager.getActivator().getBundleContext().getService( serviceReference );
        }
        catch ( Exception e )
        {
            // caused by getService() called on invalid bundle context
            // or if there is a service reference cycle involving service
            // factories !
            m_componentManager.log( LogService.LOG_ERROR, "Failed getting service {0} ({1}/{2,number,#})", new Object[]
                { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface(),
                    serviceReference.getProperty( Constants.SERVICE_ID ) }, e );
            return null;
        }

        // keep the service for later ungetting
        if ( serviceObject != null )
        {
            refPair.setServiceObject( serviceObject );
        }

        // return the acquired service (may be null of course)
        return serviceObject;
    }


    /**
     * Ungets the service described by the ServiceReference and removes it from
     * the list of bound services.
     */
//    void ungetService( ServiceReference<T> serviceReference )
//    {
//        // check we really have this service, do nothing if not
//        Map<DependencyManager<S, ?>, Map<ServiceReference<?>, RefPair<?>>> dependencyMap = m_componentManager.getDependencyMap();
//        if ( dependencyMap != null )
//        {
//            RefPair refPair  = dependencyMap.get( this ).get( serviceReference );
//            if ( refPair != null && refPair.getServiceObject() != null )
//            {
//                BundleComponentActivator activator = m_componentManager.getActivator();
//                if ( activator != null )
//                {
//                    BundleContext bundleContext = activator.getBundleContext();
//                    if ( bundleContext != null )
//                    {
//                        try
//                        {
//                            bundleContext.ungetService( serviceReference );
//                        }
//                        catch ( IllegalStateException e )
//                        {
//                            m_componentManager.log( LogService.LOG_INFO,
//                                "For dependency {0}, trying to unget ServiceReference {1} on invalid bundle context {2}",
//                                new Object[]
//                                    { m_dependencyMetadata.getName(), serviceReference.getProperty( Constants.SERVICE_ID ),
//                                        serviceReference }, null );
//                        }
//                    }
//                }
//            }
//        }
//    }


    //---------- DependencyManager core ---------------------------------------

    /**
     * Returns the name of the service reference.
     */
    public String getName()
    {
        return m_dependencyMetadata.getName();
    }


    /**
     * Returns <code>true</code> if this dependency manager is satisfied, that
     * is if either the dependency is optional or the number of services
     * registered in the framework and available to this dependency manager is
     * not zero.
     */
    public boolean isSatisfied()
    {
        return size() > 0 || m_dependencyMetadata.isOptional();
    }


    /**
     * Returns <code>true</code> if the component providing bundle has permission
     * to get the service described by this reference.
     */
    public boolean hasGetPermission()
    {
        if ( System.getSecurityManager() != null )
        {
            Permission perm = new ServicePermission( getServiceName(), ServicePermission.GET );
            return m_componentManager.getBundle().hasPermission( perm );
        }

        // no security manager, hence permission given
        return true;
    }


    boolean open( S componentInstance )
    {
        return bind( componentInstance );
    }


    /**
     * Revoke all bindings. This method cannot throw an exception since it must
     * try to complete all that it can
     * @param componentInstance
     */
    void close( S componentInstance )
    {
        unbind( componentInstance );
    }

    boolean prebind()
    {

        return customizerRef.get().open( trackerRef.get() );
//        // If no references were received, we have to check if the dependency
//        // is optional, if it is not then the dependency is invalid
//        if ( !isSatisfied() )
//        {
//            return false;
//        }
//
//        // if no bind method is configured or if this is a delayed component,
//        // we have nothing to do and just signal success
//        if ( m_dependencyMetadata.getBind() == null )
//        {
//            dependencyMap.put( this, new HashMap<ServiceReference<T>, RefPair<T>>( ) );
//            return true;
//        }
//
//        Map<ServiceReference<T>, RefPair<T>> result = new HashMap<ServiceReference<T>, RefPair<T>>();
//        // assume success to begin with: if the dependency is optional,
//        // we don't care, whether we can bind a service. Otherwise, we
//        // require at least one service to be bound, thus we require
//        // flag being set in the loop below
//        boolean success = m_dependencyMetadata.isOptional();
//
//        // Get service reference(s)
//        if ( m_dependencyMetadata.isMultiple() )
//        {
//            // bind all registered services
//            ServiceReference<T>[] refs = getFrameworkServiceReferences();
//            if ( refs != null )
//            {
//                for ( ServiceReference<T> ref : refs )
//                {
//                    RefPair refPair = new RefPair( ref );
//                    // success is if we have the minimal required number of services bound
//                    if ( m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() ) )
//                    {
//                        result.put( ref, refPair );
//                        // of course, we have success if the service is bound
//                        success = true;
//                    }
//                    else
//                    {
//                        m_componentManager.getActivator().registerMissingDependency( this, ref );
//                    }
//                }
//            }
//        }
//        else
//        {
//            // bind best matching service
//            ServiceReference ref = getFrameworkServiceReference();
//            if ( ref != null )
//            {
//                RefPair refPair = new RefPair( ref );
//                // success is if we have the minimal required number of services bound
//                if ( m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() ) )
//                {
//                    result.put( ref, refPair );
//                    // of course, we have success if the service is bound
//                    success = true;
//                }
//                else if ( isOptional() )
//                {
//                    m_componentManager.getActivator().registerMissingDependency( this, ref );
//                }
//            }
//        }
//
//        // success will be true, if the service is optional or if at least
//        // one service was available to be bound (regardless of whether the
//        // bind method succeeded or not)
//        dependencyMap.put( this, result );
//        return success;
    }

    /**
     * initializes a dependency. This method binds all of the service
     * occurrences to the instance object
     *
     * @return true if the dependency is satisfied and at least the minimum
     *      number of services could be bound. Otherwise false is returned.
     */
    private boolean bind( S componentInstance )
    {
        // If no references were received, we have to check if the dependency
        // is optional, if it is not then the dependency is invalid
        if ( !isSatisfied() )
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                "For dependency {0}, no longer satisfied, bind fails",
                new Object[]{ m_dependencyMetadata.getName() }, null );
            return false;
        }

        // if no bind method is configured or if this is a delayed component,
        // we have nothing to do and just signal success
        if ( componentInstance == null || m_dependencyMetadata.getBind() == null )
        {
            return true;
        }

        // assume success to begin with: if the dependency is optional,
        // we don't care, whether we can bind a service. Otherwise, we
        // require at least one service to be bound, thus we require
        // flag being set in the loop below
        boolean success = m_dependencyMetadata.isOptional();

        Collection<RefPair<T>> refs = customizerRef.get().getRefs( trackerRef.get() );
        m_componentManager.log( LogService.LOG_DEBUG,
            "For dependency {0}, optional: {1}; to bind: {2}",
            new Object[]{ m_dependencyMetadata.getName(), success, refs }, null );
        for ( RefPair<T> refPair : refs )
        {
            if ( !invokeBindMethod( componentInstance, refPair ) )
            {
                m_componentManager.log( LogService.LOG_DEBUG,
                        "For dependency {0}, failed to invoke bind method on object {1}",
                        new Object[] {m_dependencyMetadata.getName(), refPair}, null );

            }
            success = true;
        }
        return success;
    }


    /**
     * Handles an update in the service reference properties of a bound service.
     * <p>
     * This just calls the {@link #invokeUpdatedMethod(S, RefPair<T>)}
     * method if the method has been configured in the component metadata. If
     * the method is not configured, this method does nothing.
     *
     * @param componentInstance
     * @param refPair The <code>ServiceReference</code> representing the updated
     */
    void update( S componentInstance, final RefPair<T> refPair )
    {
        if ( m_dependencyMetadata.getUpdated() != null )
        {
            invokeUpdatedMethod( componentInstance, refPair );
        }
    }


    /**
     * Revoke the given bindings. This method cannot throw an exception since
     * it must try to complete all that it can
     */
    private void unbind( S componentInstance )
    {
            // only invoke the unbind method if there is an instance (might be null
            // in the delayed component situation) and the unbind method is declared.
            boolean doUnbind = componentInstance != null && m_dependencyMetadata.getUnbind() != null;

            for ( RefPair<T> boundRef : customizerRef.get().getRefs( trackerRef.get() ) )
            {
                if ( doUnbind )
                {
                    invokeUnbindMethod( componentInstance, boundRef );
                }

                // unget the service, we call it here since there might be a
                // bind method (or the locateService method might have been
                // called) but there is no unbind method to actually unbind
                // the service (see FELIX-832)
//                ungetService( boundRef );
            }
    }

//    boolean invokeBindMethod( S componentInstance, ServiceReference<T> ref )
//    {
//        //event driven, and we already checked this ref is not yet handled.
//        Map<DependencyManager<S, ?>, Map<ServiceReference<?>, RefPair<?>>> dependencyMap = m_componentManager.getDependencyMap();
//        if ( dependencyMap != null )
//        {
//            if ( m_bindMethods == null )
//            {
//                m_componentManager.log( LogService.LOG_ERROR,
//                        "For dependency {0}, bind method not set: component state {1}",
//                        new Object[]
//                                {m_dependencyMetadata.getName(), new Integer( m_componentManager.getState() )}, null );
//
//            }
//            Map<ServiceReference<T>, RefPair<T>> deps = (Map)dependencyMap.get( this );
//            RefPair<T> refPair = new RefPair<T>( ref );
//            if ( !m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() ) )
//            {
//                //reference deactivated while we are processing.
//                return false;
//            }
//            synchronized ( deps )
//            {
//                deps.put( ref, refPair );
//            }
//            return invokeBindMethod( componentInstance, refPair );
//        }
//        return false;
//    }

    public void invokeBindMethodLate( final ServiceReference<T> ref )
    {
        if ( !isSatisfied() )
        {
            return;
        }
        if ( !isMultiple() )
        {
            ServiceReference<T>[] refs = getFrameworkServiceReferences();
            if ( refs == null )
            {
                return; // should not happen, we have one!
            }
            // find the service with the highest ranking
            for ( int i = 1; i < refs.length; i++ )
            {
                ServiceReference<T> test = refs[i];
                if ( test.compareTo( ref ) > 0 )
                {
                    return; //another ref is better
                }
            }
        }
        //TODO static and dynamic reluctant
        RefPair<T> refPair = trackerRef.get().getService( ref );
        m_componentManager.invokeBindMethod( this, refPair );
    }

    /**
     * Calls the bind method. In case there is an exception while calling the
     * bind method, the service is not considered to be bound to the instance
     * object
     * <p>
     * If the reference is singular and a service has already been bound to the
     * component this method has no effect and just returns <code>true</code>.
     *
     *
     * @param componentInstance
     * @param refPair the service reference, service object tuple.
     *
     * @return true if the service should be considered bound. If no bind
     *      method is found or the method call fails, <code>true</code> is
     *      returned. <code>false</code> is only returned if the service must
     *      be handed over to the bind method but the service cannot be
     *      retrieved using the service reference.
     */
    boolean invokeBindMethod( S componentInstance, RefPair refPair )
    {
        // The bind method is only invoked if the implementation object is not
        // null. This is valid for both immediate and delayed components
        if ( componentInstance != null )
        {
            MethodResult result = m_bindMethods.getBind().invoke( componentInstance, refPair, MethodResult.VOID );
            if ( result == null )
            {
                return false;
            }
            m_componentManager.setServiceProperties( result );
            return true;

            // Concurrency Issue: The component instance still exists but
            // but the defined bind method field is null, fail binding
//            m_componentManager.log( LogService.LOG_INFO,
//                    "DependencyManager : Component instance present, but DependencyManager shut down (no bind method)",
//                    null );
//            return false;
        }
        else
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                    "DependencyManager : component not yet created, assuming bind method call succeeded",
                    null );

            return true;
        }
    }


    /**
     * Calls the updated method.
     *
     * @param componentInstance
     * @param refPair A service reference corresponding to the service whose service
     */
    private void invokeUpdatedMethod( S componentInstance, final RefPair<T> refPair )
    {
        // The updated method is only invoked if the implementation object is not
        // null. This is valid for both immediate and delayed components
        if ( componentInstance != null )
        {
            if (refPair == null)
            {

                //TODO should this be possible? If so, reduce or eliminate logging
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUpdatedMethod : Component set, but reference not present", null );
                return;
            }
            if ( !m_bindMethods.getUpdated().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() ))
            {
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUpdatedMethod : Service not available from service registry for ServiceReference {0} for reference {1}",
                        new Object[] {refPair.getRef(), getName()}, null );
                return;

            }
            MethodResult methodResult = m_bindMethods.getUpdated().invoke( componentInstance, refPair, MethodResult.VOID );
            if ( methodResult != null)
            {
                m_componentManager.setServiceProperties( methodResult );
            }
        }
        else
        {
            // don't care whether we can or cannot call the updated method
            // if the component instance has already been cleared by the
            // close() method
            m_componentManager.log( LogService.LOG_DEBUG,
                    "DependencyManager : Component not set, no need to call updated method", null );
        }
    }


    /**
     * Calls the unbind method.
     * <p>
     * If the reference is singular and the given service is not the one bound
     * to the component this method has no effect and just returns
     * <code>true</code>.
     *
     * @param componentInstance
     * @param refPair A service reference corresponding to the service that will be
     */
    void invokeUnbindMethod( S componentInstance, final RefPair<T> refPair )
    {
        // The unbind method is only invoked if the implementation object is not
        // null. This is valid for both immediate and delayed components
        if ( componentInstance != null )
        {
            if (refPair == null)
            {
                //TODO should this be possible? If so, reduce or eliminate logging
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUnbindMethod : Component set, but reference not present", null );
                return;
            }
            if ( !m_bindMethods.getUnbind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext() ))
            {
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUnbindMethod : Service not available from service registry for ServiceReference {0} for reference {1}",
                        new Object[] {refPair.getRef(), getName()}, null );
                return;

            }
            MethodResult methodResult = m_bindMethods.getUnbind().invoke( componentInstance, refPair, MethodResult.VOID );
            if ( methodResult != null )
            {
                m_componentManager.setServiceProperties( methodResult );
            }
        }
        else
        {
            // don't care whether we can or cannot call the unbind method
            // if the component instance has already been cleared by the
            // close() method
            m_componentManager.log( LogService.LOG_DEBUG,
                "DependencyManager : Component not set, no need to call unbind method", null );
        }
    }


    //------------- Service target filter support -----------------------------

    /**
     * Returns <code>true</code> if the <code>properties</code> can be
     * dynamically applied to the component to which the dependency manager
     * belongs.
     * <p>
     * This method applies the following heuristics (in the given order):
     * <ol>
     * <li>If there is no change in the target filter for this dependency, the
     * properties can be applied</li>
     * <li>If the dependency is static and there are changes in the target
     * filter we cannot dynamically apply the configuration because the filter
     * may (assume they do for simplicity here) cause the bindings to change.</li>
     * <li>If there is still at least one service matching the new target filter
     * we can apply the configuration because the depdency is dynamic.</li>
     * <li>If there are no more services matching the filter, we can still
     * apply the configuration if the dependency is optional.</li>
     * <li>Ultimately, if all other checks do not apply we cannot dynamically
     * apply.</li>
     * </ol>
     */
    boolean canUpdateDynamically( Dictionary<String, Object> properties )
    {
        // 1. no target filter change
        final String newTarget = ( String ) properties.get( m_dependencyMetadata.getTargetPropertyName() );
        final String currentTarget = getTarget();
        if ( ( currentTarget == null && newTarget == null )
            || ( currentTarget != null && currentTarget.equals( newTarget ) ) )
        {
            // can update if target filter is not changed, since there is
            // no change is service binding
            return true;
        }
        // invariant: target filter change

        // 2. if static policy, cannot update dynamically
        // (for simplicity assuming change in target service binding)
        if ( m_dependencyMetadata.isStatic() )
        {
            // cannot update if services are statically bound and the target
            // filter is modified, since there is (potentially at least)
            // a change is service bindings
            return false;
        }
        // invariant: target filter change + dynamic policy

        // 3. check target services matching the new filter
        ServiceReference<T>[] refs = getFrameworkServiceReferences( newTarget );
        if ( refs != null && refs.length > 0 )
        {
            // can update since there is at least on service matching the
            // new target filter and the services may be exchanged dynamically
            return true;
        }
        // invariant: target filter change + dynamic policy + no more matching service

        // 4. check optionality
        if ( m_dependencyMetadata.isOptional() )
        {
            // can update since even if no service matches the new filter, this
            // makes no difference because the dependency is optional
            return true;
        }
        // invariant: target filter change + dynamic policy + no more matching service + required

        // 5. cannot dynamically update because the target filter results in
        // no more applicable services which is not acceptable
        return false;
    }


    /**
     * Sets the target filter from target filter property contained in the
     * properties. The filter is taken from a property whose name is derived
     * from the dependency name and the suffix <code>.target</code> as defined
     * for target properties on page 302 of the Declarative Services
     * Specification, section 112.6.
     *
     * @param properties The properties containing the optional target service
     *      filter property
     */
    void setTargetFilter( Dictionary<String, Object> properties )
    {
        try
        {
            setTargetFilter( ( String ) properties.get( m_dependencyMetadata.getTargetPropertyName() ) );
        }
        catch ( InvalidSyntaxException e )
        {
            // this should not occur.  The only choice would be if the filter for the object class was invalid,
            // but we already set this once when we enabled.
        }
    }


    /**
     * Sets the target filter of this dependency to the new filter value. If the
     * new target filter is the same as the old target filter, this method has
     * not effect. Otherwise any services currently bound but not matching the
     * new filter are unbound. Likewise any registered services not currently
     * bound but matching the new filter are bound.
     *
     * @param target The new target filter to be set. This may be
     *      <code>null</code> if no target filtering is to be used.
     */
    private void setTargetFilter( String target ) throws InvalidSyntaxException
    {
        // do nothing if target filter does not change
        if ( ( m_target == null && target == null ) || ( m_target != null && m_target.equals( target ) ) )
        {
            m_componentManager.log( LogService.LOG_DEBUG, "No change in target property for dependency {0}: currently registered: {1}", new Object[]
                    {m_dependencyMetadata.getName(), registered}, null );
            if (registered)
            {
                return;
            }
        }
        m_target = target;
        String filterString = "(" + Constants.OBJECTCLASS + "=" + m_dependencyMetadata.getInterface() + ")";
        if (m_target != null)
        {
            filterString = "(&" + filterString + m_target + ")";
        }

        if ( registered )
        {
            unregisterServiceListener();
        }
        m_componentManager.log( LogService.LOG_DEBUG, "Setting target property for dependency {0} to {1}", new Object[]
                {m_dependencyMetadata.getName(), target}, null );
        try
        {
            m_targetFilter = m_componentManager.getActivator().getBundleContext().createFilter( filterString );
        }
        catch ( InvalidSyntaxException ise )
        {
            m_componentManager.log( LogService.LOG_ERROR, "Invalid syntax in target property for dependency {0} to {1}", new Object[]
                    {m_dependencyMetadata.getName(), target}, null );
            // TODO this is an error, how do we recover?
            m_targetFilter = null;
        }

        registerServiceListener();

        //TODO deal with changes in what matches filter.


//
//        //wait for events to finish processing
//        synchronized ( added )
//        {
//            while ( !added.isEmpty() )
//            {
//                try
//                {
//                    added.wait();
//                }
//                catch ( InterruptedException e )
//                {
//                    //??
//                }
//            }
//        }
//        synchronized ( removed )
//        {
//            while ( !removed.isEmpty() )
//            {
//                try
//                {
//                    removed.wait();
//                }
//                catch ( InterruptedException e )
//                {
//                    //??
//                }
//            }
//        }
//
//        //we are now done processing all the events received before we removed the listener.
//        ServiceReference[] boundRefs = getBoundServiceReferences();
//        if ( boundRefs != null && m_targetFilter != null )
//        {
//            for ( ServiceReference boundRef : boundRefs )
//            {
//                if ( !m_targetFilter.match( boundRef ) )
//                {
//                    serviceRemoved( boundRef );
//                }
//            }
//        }
//        boolean active = m_componentManager.getDependencyMap() != null;
//        // register the service listener
//        registerServiceListener();
//        Collection<ServiceReference> toAdd = new ArrayList<ServiceReference>();
//
//        synchronized ( enableLock )
//        {
//            // get the current number of registered services available
//            ServiceReference[] refArray = getFrameworkServiceReferences();
//            if ( refArray != null )
//            {
//                List<ServiceReference> refs = Arrays.asList( refArray );
//                m_componentManager.log( LogService.LOG_DEBUG, "Component: {0} dependency: {1} refs: {2}", new Object[]
//                        {m_componentManager.getName(), getName(), refs}, null );
//                synchronized ( added )
//                {
//                    m_componentManager.log( LogService.LOG_DEBUG, "Component: {0} dependency: {1} added: {2}", new Object[]
//                            {m_componentManager.getName(), getName(), added}, null );
//                    added.removeAll( refs );
//                }
//                synchronized ( removed )
//                {
//                    m_componentManager.log( LogService.LOG_DEBUG, "Component: {0} dependency: {1} removed: {2}", new Object[]
//                            {m_componentManager.getName(), getName(), removed}, null );
//                    removed.retainAll( refs );
//                }
//                if ( active )
//                {
//                    for ( ServiceReference ref : refs )
//                    {
//                        if ( getBoundService( ref ) == null )
//                        {
//                            toAdd.add( ref );
//                        }
//                    }
//                }
//            }
//            else
//            {
//                m_componentManager.log( LogService.LOG_DEBUG, "Component: {0} dependency: {1} no services", new Object[]
//                        {m_componentManager.getName(), getName()}, null );
//                removed.clear();//retainAll of empty set.
//            }
//            m_size.set( ( refArray == null ) ? 0 : refArray.length );
//        }
//
//        for ( ServiceReference ref : toAdd )
//        {
//            serviceAdded( ref );
//        }

    }

    private void registerServiceListener() throws InvalidSyntaxException
    {
        ServiceTracker<T, RefPair<T>> tracker = new ServiceTracker<T, RefPair<T>>( m_componentManager.getActivator().getBundleContext(), m_targetFilter, newCustomizer() );
        tracker.open();
        trackerRef.set( tracker );
        registered = true;
        m_componentManager.log( LogService.LOG_DEBUG, "registering service listener for dependency {0}", new Object[]
                {m_dependencyMetadata.getName()}, null );
    }

    private Customizer<T> newCustomizer()
    {
        Customizer<T> customizer;
        if (m_componentManager.isFactory())
        {
            customizer = new FactoryCustomizer();
        }
        else if ( isMultiple() )
        {
            if ( isStatic() )
            {
                if ( isReluctant() )
                {
                    customizer = new MultipleStaticReluctantCustomizer();
                }
                else
                {
                    customizer = new MultipleStaticGreedyCustomizer();
                }
            }
            else
            {
                customizer = new MultipleDynamicCustomizer();
            }
        }
        else
        {
            if ( isStatic() )
            {
                customizer = new SingleStaticCustomizer();
            }
            else
            {
                customizer = new SingleDynamicCustomizer();
            }
        }
        customizerRef.set( customizer );
        return customizer;
    }

    void unregisterServiceListener()
    {
        ServiceTracker<T, RefPair<T>> tracker = trackerRef.get();
        trackerRef.set( null ); //???
        tracker.close();
        registered = false;
        m_componentManager.log( LogService.LOG_DEBUG, "unregistering service listener for dependency {0}", new Object[]
                {m_dependencyMetadata.getName()}, null );
    }


    /**
     * Returns the target filter of this dependency as a string or
     * <code>null</code> if this dependency has no target filter set.
     *
     * @return The target filter of this dependency or <code>null</code> if
     *      none is set.
     */
    public String getTarget()
    {
        return m_target;
    }


    /**
     * Checks whether the service references matches the target filter of this
     * dependency.
     *
     * @param ref The service reference to check
     * @return <code>true</code> if this dependency has no target filter or if
     *      the target filter matches the service reference.
     */
    private boolean targetFilterMatch( ServiceReference ref )
    {
        return m_targetFilter == null || m_targetFilter.match( ref );
    }


    public String toString()
    {
        return "DependencyManager: Component [" + m_componentManager + "] reference [" + m_dependencyMetadata.getName() + "]";
    }
}
