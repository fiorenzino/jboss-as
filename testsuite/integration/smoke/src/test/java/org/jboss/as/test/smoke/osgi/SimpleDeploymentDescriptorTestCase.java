/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.test.smoke.osgi;

import static org.jboss.as.test.osgi.OSGiManagementOperations.getBundleId;
import static org.jboss.as.test.osgi.OSGiManagementOperations.getBundleState;

import java.io.InputStream;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper.DeploymentOverlay;
import org.jboss.as.test.smoke.osgi.bundleA.SimpleActivator;
import org.jboss.osgi.spi.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ClassLoaderAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.impl.base.asset.AssetUtil;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xml.sax.InputSource;

/**
 * Test META-INF/jboss-all.xml descriptor with bundle deployments.
 *
 * @author thomas.diesler@jboss.com
 * @since 05-Sep-2012
 */
@RunAsClient
@RunWith(Arquillian.class)
public class SimpleDeploymentDescriptorTestCase {

    private static final String BUNDLE_A = "bundle-a.jar";
    private static final String BUNDLE_B = "bundle-b.jar";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static Archive<?> getDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "dummy.jar");
    }

    @Test
    public void testBundleContainedDescriptor() throws Exception {

        deployer.deploy(BUNDLE_A);
        try {
            Long bundleId = getBundleId(getControllerClient(), "bundle-a", null);
            Assert.assertTrue("Bundle found", bundleId > 0);
            Assert.assertEquals("INSTALLED", getBundleState(getControllerClient(), bundleId));
        } finally {
            deployer.undeploy(BUNDLE_A);
        }
    }

    @Test
    public void testExternalDescriptor() throws Exception {

        String resourceName = AssetUtil.getClassLoaderResourceName(SimpleActivator.class.getPackage(), "jboss-all.xml");
        InputSource inputSource = new InputSource(new ClassLoaderAsset(resourceName).openStream());
        inputSource.setPublicId("urn:jboss:deployment-dependencies:1.0");
        DeploymentOverlay overlay = new DeploymentOverlay("META-INF/jboss-all.xml", inputSource.getByteStream());

        ServerDeploymentHelper server = new ServerDeploymentHelper(getControllerClient());
        InputStream input = deployer.getDeployment(BUNDLE_B);
        server.deploy(BUNDLE_B, input, overlay);
        try {
            Long bundleId = getBundleId(getControllerClient(), "bundle-b", null);
            Assert.assertTrue("Bundle found", bundleId > 0);
            Assert.assertEquals("INSTALLED", getBundleState(getControllerClient(), bundleId));
        } finally {
            server.undeploy(new String[] { BUNDLE_B });
        }
    }

    private ModelControllerClient getControllerClient() {
        return managementClient.getControllerClient();
    }

    @Deployment(name = BUNDLE_A, managed = false, testable = false)
    public static JavaArchive getBundleA() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_A);
        archive.addAsManifestResource(SimpleActivator.class.getPackage(), "jboss-all.xml", "jboss-all.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("bundle-a");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = BUNDLE_B, managed = false, testable = false)
    public static JavaArchive getBundleB() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_B);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("bundle-b");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }
}
