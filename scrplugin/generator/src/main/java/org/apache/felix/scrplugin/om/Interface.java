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
package org.apache.felix.scrplugin.om;

import org.apache.felix.scrplugin.annotations.ScannedAnnotation;

/**
 * <code>Interface</code>
 *
 * contains an interface name the component is implementing
 * (the interface name can also be a class name)
 */
public class Interface extends AbstractObject {

    /** Name of the interface. */
    private String interfaceName;

    /**
     * Constructor from java source.
     */
    public Interface(final ScannedAnnotation annotation, final String sourceLocation) {
        super(annotation, sourceLocation);
    }

    /**
     * Get the interface name.
     */
    public String getInterfaceName() {
        return this.interfaceName;
    }

    /**
     * Set the interface name.
     */
    public void setInterfaceName(final String name) {
        this.interfaceName = name;
    }
}
