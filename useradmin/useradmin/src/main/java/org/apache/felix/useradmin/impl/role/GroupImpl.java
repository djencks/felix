/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.felix.useradmin.impl.role;

import java.util.Collection;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;

import org.osgi.service.useradmin.Group;
import org.osgi.service.useradmin.Role;
import org.osgi.service.useradmin.UserAdminPermission;

/**
 * Provides an implementation of {@link Group}. 
 */
public class GroupImpl extends UserImpl implements Group {

    private static final long serialVersionUID = 1515097730006454140L;
    
	private static final String BASIC_MEMBER = "basicMember";
    private static final String REQUIRED_MEMBER = "requiredMember";

    private final Object m_lock = new Object();

    private final Map m_members;
    private final Map m_requiredMembers;

    /**
     * Creates a new {@link GroupImpl} instance of type {@link Role#GROUP}.
     * 
     * @param name the name of this group role, cannot be <code>null</code> or empty.
     */
    public GroupImpl(String name) {
        super(Role.GROUP, name);
        
        m_members = new HashMap();
        m_requiredMembers = new HashMap();
    }

    /**
     * Creates a new {@link GroupImpl} instance of type {@link Role#GROUP}.
     * 
     * @param name the name of this group role, cannot be <code>null</code> or empty.
     */
    public GroupImpl(String name, Dictionary properties, Dictionary credentials) {
        super(Role.GROUP, name, properties, credentials);

        m_members = new HashMap();
        m_requiredMembers = new HashMap();
    }

    /**
     * {@inheritDoc}
     */
    public boolean addMember(Role role) {
        checkPermissions();
        
        Object result;
        synchronized (m_lock) {
            if (m_members.containsKey(role.getName()) || m_requiredMembers.containsKey(role.getName())) {
                return false;
            }
            result = m_members.put(role.getName(), role);
        }

        if (result == null) {
            // Notify our (optional) listener...
            entryAdded(BASIC_MEMBER, role);
        }
        
        return (result == null);
    }

    /**
     * {@inheritDoc}
     */
    public boolean addRequiredMember(Role role) {
        checkPermissions();

        Object result;
        synchronized (m_lock) {
            if (m_requiredMembers.containsKey(role.getName()) || m_members.containsKey(role.getName())) {
                return false;
            }
            result = m_requiredMembers.put(role.getName(), role);
        }

        if (result == null) {
            // Notify our (optional) listener...
            entryAdded(REQUIRED_MEMBER, role);
        }
        
        return (result == null);
    }

    /**
     * {@inheritDoc}
     */
    public Role[] getMembers() {
        Role[] roles;
        synchronized (m_lock) {
            Collection values = m_members.values();
            roles = (Role[]) values.toArray(new Role[values.size()]);
        }

        return (roles.length == 0) ? null : roles;
    }

    /**
     * {@inheritDoc}
     */
    public Role[] getRequiredMembers() {
        Role[] roles;
        synchronized (m_lock) {
            Collection values = m_requiredMembers.values();
            roles = (Role[]) values.toArray(new Role[values.size()]);
        }

        return (roles.length == 0) ? null : roles;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeMember(Role role) {
        checkPermissions();

        String key = null;
        Object result = null;
        
        synchronized (m_lock) {
            if (m_requiredMembers.containsKey(role.getName())) {
                key = REQUIRED_MEMBER;
                result = m_requiredMembers.remove(role.getName());
            }
            else if (m_members.containsKey(role.getName())) {
                key = BASIC_MEMBER;
                result = m_members.remove(role.getName());
            }
        }

        if (result != null) {
            // Notify our (optional) listener...
            entryRemoved(key);
        }
        
        return result != null;
    }
    
    /**
     * {@inheritDoc}
     */
    public String toString() {
        return "Group(" + getName() + "): R{" + m_requiredMembers + "}, B{" + m_members + "}";
    }

    /**
     * Verifies whether the caller has the right permissions to get or change the given key.
     * 
     * @throws SecurityException in case the caller has not the right permissions to perform the action.
     */
    private void checkPermissions() throws SecurityException {
        SecurityManager securityManager = System.getSecurityManager();
        if (securityManager != null) {
            securityManager.checkPermission(new UserAdminPermission(UserAdminPermission.ADMIN, null));
        }
    }
}
