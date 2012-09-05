/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.deployment.dependencies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.as.server.deployment.AttachmentKey;

/**
 * @author Stuart Douglas
 * @author Thomas.Diesler@jboss.com
 */
public class DeploymentDependencies {

    public static final AttachmentKey<DeploymentDependencies> ATTACHMENT_KEY = AttachmentKey.create(DeploymentDependencies.class);

    private final List<String> dependencies = new ArrayList<String>();
    private final Map<String, String> properties = new HashMap<String,String>();

    public List<String> getDependencies() {
        return Collections.unmodifiableList(dependencies);
    }

    void addDependency(String dependency) {
        dependencies.add(dependency);
    }

    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public String getProperty(String key) {
        return properties.get(key);
    }

    void addProperty(String key, String value) {
        properties.put(key, value);
    }

}
