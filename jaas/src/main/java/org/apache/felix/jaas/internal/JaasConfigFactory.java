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

package org.apache.felix.jaas.internal;

import static org.apache.felix.jaas.internal.Util.trimToNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.felix.jaas.LoginModuleFactory;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyOption;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.log.LogService;

@Component(label = "%jaas.name", description = "%jaas.description", metatype = true, ds = false, name = JaasConfigFactory.SERVICE_PID, configurationFactory = true)
public class JaasConfigFactory implements ManagedServiceFactory
{

    public static final String SERVICE_PID = "org.apache.felix.jaas.Configuration.factory";

    @Property
    static final String JAAS_CLASS_NAME = "jaas.classname";

    @Property(value = "required", options = {
            @PropertyOption(name = "required", value = "%jaas.flag.required"),
            @PropertyOption(name = "requisite", value = "%jaas.flag.requisite"),
            @PropertyOption(name = "sufficient", value = "%jaas.flag.sufficient"),
            @PropertyOption(name = "optional", value = "%jaas.flag.optional") })
    static final String JAAS_CONTROL_FLAG = "jaas.controlFlag";

    @Property(intValue = 0)
    static final String JAAS_RANKING = "jaas.ranking";

    @Property(unbounded = PropertyUnbounded.ARRAY)
    static final String JAAS_OPTIONS = "jaas.options";

    @Property
    static final String JAAS_REALM_NAME = "jaas.realmName";

    private final Logger log;

    private final LoginModuleCreator factory;

    private final BundleContext context;

    private final Map<String, ServiceRegistration> registrations = new ConcurrentHashMap<String, ServiceRegistration>();

    public JaasConfigFactory(BundleContext context, LoginModuleCreator factory, Logger log)
    {
        this.context = context;
        this.factory = factory;
        this.log = log;

        Properties props = new Properties();
        props.setProperty(Constants.SERVICE_VENDOR, "Apache Software Foundation");
        props.setProperty(Constants.SERVICE_PID, SERVICE_PID);
        context.registerService(ManagedServiceFactory.class.getName(), this, props);
    }

    @Override
    public String getName()
    {
        return "JaasConfigFactory";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void updated(String pid, Dictionary config) throws ConfigurationException
    {
        String className = trimToNull(Util.toString(config.get(JAAS_CLASS_NAME), null));
        String flag = trimToNull(Util.toString(config.get(JAAS_CONTROL_FLAG), "required"));
        int ranking = Util.toInteger(config.get(JAAS_RANKING), 0);

        String[] props = Util.toStringArray(config.get(JAAS_OPTIONS), new String[0]);
        Map options = toMap(props);
        String realmName = trimToNull(Util.toString(config.get(JAAS_REALM_NAME), null));

        if (className == null)
        {
            log.log(LogService.LOG_WARNING,
                "Class name for the LoginModule is required. Configuration would be ignored"
                    + config);
            return;
        }

        //Combine the config. As the jaas.options is required for capturing config
        //via felix webconsole. However in normal usage people would like to provide
        //key=value pair directly in config. So merge both to provide a combined
        //view
        Map combinedOptions = convertToMap(config);
        combinedOptions.putAll(options);

        LoginModuleProvider lmf = new ConfigLoginModuleProvider(realmName, className,
            combinedOptions, ControlFlag.from(flag).flag(), ranking, factory);

        ServiceRegistration reg = context.registerService(
            LoginModuleFactory.class.getName(), lmf, new Properties());
        registrations.put(pid, reg);
    }

    @Override
    public void deleted(String pid)
    {
        ServiceRegistration reg = registrations.remove(pid);
        if (reg != null)
        {
            reg.unregister();
        }
    }

    //~----------------------------------- Utility Methods

    private static Map<String, Object> toMap(String[] props)
    {
        //TODO support system property substitution e.g. ${user.home}
        //in property values
        Map<String, Object> result = new HashMap<String, Object>();
        for (String kv : props)
        {
            int indexOfEqual = kv.indexOf('=');
            if (indexOfEqual > 0)
            {
                String key = trimToNull(kv.substring(0, indexOfEqual));
                String value = trimToNull(kv.substring(indexOfEqual + 1));
                if (key != null && value != null)
                {
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map convertToMap(Dictionary config)
    {
        Map copy = new HashMap();
        Enumeration e = config.keys();
        while (e.hasMoreElements())
        {
            Object key = e.nextElement();
            Object value = config.get(key);
            copy.put(key, value);
        }
        return copy;
    }
}
