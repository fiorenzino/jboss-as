/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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

package org.jboss.as.osgi.deployment;

import static org.jboss.as.controller.client.helpers.ClientConstants.COMPOSITE;
import static org.jboss.as.controller.client.helpers.ClientConstants.CONTENT;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_METADATA_START_POLICY;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_OVERLAY_OPERATION;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_REMOVE_OPERATION;
import static org.jboss.as.controller.client.helpers.ClientConstants.OP;
import static org.jboss.as.controller.client.helpers.ClientConstants.OP_ADDR;
import static org.jboss.as.controller.client.helpers.ClientConstants.OUTCOME;
import static org.jboss.as.controller.client.helpers.ClientConstants.STEPS;
import static org.jboss.as.controller.client.helpers.ClientConstants.SUCCESS;
import static org.jboss.as.server.Services.JBOSS_SERVER_CONTROLLER;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.ManagedBean;

import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.ClientConstants.StartPolicy;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper.DeploymentOverlay;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper.ServerDeploymentException;
import org.jboss.as.ee.structure.DeploymentType;
import org.jboss.as.ee.structure.DeploymentTypeMarker;
import org.jboss.as.osgi.DeploymentMarker;
import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.osgi.OSGiLogger;
import org.jboss.as.osgi.service.BundleInstallIntegration;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.EjbDeploymentMarker;
import org.jboss.as.server.deployment.JPADeploymentMarker;
import org.jboss.as.server.deployment.annotation.CompositeIndex;
import org.jboss.as.server.deployment.dependencies.DeploymentDependencies;
import org.jboss.as.server.deployment.jbossallxml.JBossAllXMLParsingProcessor;
import org.jboss.as.server.deployment.module.ModuleSpecification;
import org.jboss.as.server.deployment.module.ResourceRoot;
import org.jboss.dmr.ModelNode;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.deployment.deployer.DeploymentFactory;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.spi.BundleInfo;
import org.jboss.vfs.VirtualFile;

/**
 * Processes deployments that have OSGi metadata attached.
 *
 * If so, it creates an {@link Deployment}.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 20-Sep-2010
 */
public class BundleDeploymentProcessor implements DeploymentUnitProcessor {

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {

        final DeploymentUnit depUnit = phaseContext.getDeploymentUnit();
        final String contextName = depUnit.getName();

        // Check if {@link BundleInstallIntegration} provided the {@link Deployment}
        Deployment deployment = BundleInstallIntegration.removeDeployment(contextName);
        if (deployment != null) {
            deployment.setAutoStart(false);
        }

        // Check for attached BundleInfo
        BundleInfo info = depUnit.getAttachment(OSGiConstants.BUNDLE_INFO_KEY);
        if (deployment == null && info != null) {
            deployment = DeploymentFactory.createDeployment(info);
            deployment.addAttachment(BundleInfo.class, info);
            StartPolicy startPolicy = getStartPolicy(depUnit);
            OSGiMetaData metadata = info.getOSGiMetadata();
            deployment.setAutoStart(startPolicy == StartPolicy.AUTO && !metadata.isFragment());

            // Prevent autostart for marked deployments
            AnnotationInstance marker = getAnnotation(depUnit, DeploymentMarker.class.getName());
            if (deployment.isAutoStart() && marker != null) {
                AnnotationValue value = marker.value("autoStart");
                deployment.setAutoStart(Boolean.parseBoolean(value.asString()));
            }
        }

        // Attach the deployment
        if (deployment != null) {

            // Make sure the framework uses the same module id as the server
            ModuleIdentifier identifier = depUnit.getAttachment(Attachments.MODULE_IDENTIFIER);
            deployment.addAttachment(ModuleIdentifier.class, identifier);

            // Allow additional dependencies for the set of supported deployemnt types
            if (allowAdditionalModuleDependencies(depUnit)) {
                ModuleSpecification moduleSpec = depUnit.getAttachment(Attachments.MODULE_SPECIFICATION);
                deployment.addAttachment(ModuleSpecification.class, moduleSpec);
            } else {
                // Make this module private so that other modules in the deployment don't create a direct dependency
                ModuleSpecification moduleSpec = depUnit.getAttachment(Attachments.MODULE_SPECIFICATION);
                moduleSpec.setPrivateModule(true);
            }

            // Attach the bundle deployment
            depUnit.putAttachment(OSGiConstants.DEPLOYMENT_KEY, deployment);

            // Add the {@link DeploymentOverlay} if we found one
            ResourceRoot root = depUnit.getAttachment(Attachments.DEPLOYMENT_ROOT);
            for (String overlayPath : JBossAllXMLParsingProcessor.DEPLOYMENT_STRUCTURE_DESCRIPTOR_LOCATIONS) {
                VirtualFile file = root.getRoot().getChild(overlayPath);
                if (file.exists()) {
                    DeploymentOverlay overlay = new DeploymentOverlay(overlayPath, null);
                    DeploymentOverlayRemoveService.addService(phaseContext.getServiceTarget(), depUnit, overlay);
                    break;
                }
            }
        }
    }

    static AnnotationInstance getAnnotation(DeploymentUnit depUnit, String className) {
        CompositeIndex index = depUnit.getAttachment(Attachments.COMPOSITE_ANNOTATION_INDEX);
        List<AnnotationInstance> annotations = index.getAnnotations(DotName.createSimple(className));
        return annotations.size() == 1 ? annotations.get(0) : null;
    }

    private boolean allowAdditionalModuleDependencies(DeploymentUnit depUnit) {
        boolean isWar = DeploymentTypeMarker.isType(DeploymentType.WAR, depUnit);
        boolean isEjb = EjbDeploymentMarker.isEjbDeployment(depUnit);
        boolean isCDI = getAnnotation(depUnit, ManagedBean.class.getName()) != null;
        boolean isJPA = JPADeploymentMarker.isJPADeployment(depUnit);
        return isWar || isEjb || isCDI || isJPA;
    }

    @Override
    public void undeploy(DeploymentUnit depUnit) {
        depUnit.removeAttachment(OSGiConstants.DEPLOYMENT_KEY);
    }

    static DeploymentDependencies getDeploymentMetadata(final DeploymentUnit depUnit) {
        DeploymentDependencies metadata = depUnit.getAttachment(DeploymentDependencies.ATTACHMENT_KEY);
        if (metadata == null && depUnit.getParent() != null) {
            metadata = getDeploymentMetadata(depUnit.getParent());
        }
        return metadata != null ? metadata : new DeploymentDependencies();
    }

    static StartPolicy getStartPolicy(DeploymentUnit depUnit) {
        DeploymentDependencies metadata = getDeploymentMetadata(depUnit);
        return StartPolicy.parse(metadata.getProperties().get(DEPLOYMENT_METADATA_START_POLICY));
    }

    static class DeploymentOverlayRemoveService extends AbstractService<Void> {

        private final InjectedValue<ModelController> injectedController = new InjectedValue<ModelController>();
        private final InjectedValue<DeploymentUnit> injectedDeploymentUnit = new InjectedValue<DeploymentUnit>();
        private final DeploymentOverlay overlay;

        static void addService(ServiceTarget serviceTarget, DeploymentUnit depUnit, DeploymentOverlay overlay) {
            DeploymentOverlayRemoveService service = new DeploymentOverlayRemoveService(depUnit, overlay);
            ServiceName serviceName = depUnit.getServiceName().append("overlay-remove");
            ServiceBuilder<Void> builder = serviceTarget.addService(serviceName, service);
            builder.addDependency(JBOSS_SERVER_CONTROLLER, ModelController.class, service.injectedController);
            builder.addDependency(depUnit.getServiceName(), DeploymentUnit.class, service.injectedDeploymentUnit);
            builder.install();
        }

        private DeploymentOverlayRemoveService(DeploymentUnit depUnit, DeploymentOverlay overlay) {
            this.overlay = overlay;
        }


        @Override
        public void stop(StopContext context) {
            ModelController modelController = injectedController.getValue();
            DeploymentUnit depUnit = injectedDeploymentUnit.getValue();
            ModelControllerClient client = modelController.createClient(Executors.newSingleThreadExecutor());
            String runtimeName = depUnit.getName();
            try {
                if (hasOverlay(client, runtimeName, overlay)) {
                    removeOverlay(client, runtimeName, overlay);
                }
            } catch (ServerDeploymentException ex) {
                OSGiLogger.LOGGER.warnCannotRemoveDeploymentMetadata(ex, runtimeName);
            }
        }

        private boolean hasOverlay(ModelControllerClient client, String runtimeName, DeploymentOverlay overlay) throws ServerDeploymentException {

            ModelNode op = new ModelNode();
            op.get(OP_ADDR).add(DEPLOYMENT_OVERLAY_OPERATION, overlay.getRuntimeName(runtimeName));
            op.get(OP).set("read-resource");

            ModelNode resultNode;
            try {
                Future<ModelNode> future = client.executeAsync(op, null);
                resultNode = future.get();
            } catch (Exception ex) {
                throw new ServerDeploymentException(ex);
            }

            return SUCCESS.equals(resultNode.get(OUTCOME).asString());
        }

        private void removeOverlay(ModelControllerClient client, String runtimeName, DeploymentOverlay overlay) throws ServerDeploymentException {

            ModelNode op = new ModelNode();
            OperationBuilder builder = new OperationBuilder(op);
            op.get(OP_ADDR).setEmptyList();
            op.get(OP).set(COMPOSITE);
            ModelNode steps = op.get(STEPS);
            steps.setEmptyList();

            ModelNode step = new ModelNode();
            ModelNode addr = new ModelNode();
            addr.add(DEPLOYMENT_OVERLAY_OPERATION, overlay.getRuntimeName(runtimeName));
            addr.add(CONTENT, overlay.getPath());
            step.get(OP_ADDR).set(addr);
            step.get(OP).set(DEPLOYMENT_REMOVE_OPERATION);
            steps.add(step);

            step = new ModelNode();
            builder = new OperationBuilder(op);
            addr = new ModelNode();
            addr.add(DEPLOYMENT_OVERLAY_OPERATION, overlay.getRuntimeName(runtimeName));
            addr.add(DEPLOYMENT, runtimeName);
            step.get(OP_ADDR).set(addr);
            step.get(OP).set(DEPLOYMENT_REMOVE_OPERATION);
            steps.add(step);

            step = new ModelNode();
            builder = new OperationBuilder(op);
            step.get(OP_ADDR).add(DEPLOYMENT_OVERLAY_OPERATION, overlay.getRuntimeName(runtimeName));
            step.get(OP).set(DEPLOYMENT_REMOVE_OPERATION);
            steps.add(step);

            ServerDeploymentHelper deploymentHelper = new ServerDeploymentHelper(client);
            deploymentHelper.executeCompositeOperation(builder.build(), steps.asList().size());
        }
    }
}
