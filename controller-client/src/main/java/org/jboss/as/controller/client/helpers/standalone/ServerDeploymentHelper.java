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
package org.jboss.as.controller.client.helpers.standalone;

import static org.jboss.as.controller.client.helpers.ClientConstants.ADD;
import static org.jboss.as.controller.client.helpers.ClientConstants.COMPOSITE;
import static org.jboss.as.controller.client.helpers.ClientConstants.CONTENT;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_DEPLOY_OPERATION;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_OVERLAY_OPERATION;
import static org.jboss.as.controller.client.helpers.ClientConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.client.helpers.ClientConstants.INPUT_STREAM_INDEX;
import static org.jboss.as.controller.client.helpers.ClientConstants.OP;
import static org.jboss.as.controller.client.helpers.ClientConstants.OP_ADDR;
import static org.jboss.as.controller.client.helpers.ClientConstants.OUTCOME;
import static org.jboss.as.controller.client.helpers.ClientConstants.RUNTIME_NAME;
import static org.jboss.as.controller.client.helpers.ClientConstants.STEPS;
import static org.jboss.as.controller.client.helpers.ClientConstants.SUCCESS;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.dmr.ModelNode;

/**
 * A simple helper for server deployments.
 *
 * @author thomas.diesler@jboss.com
 * @since 22-Mar-2012
 */
public class ServerDeploymentHelper {

    private final ServerDeploymentManager deploymentManager;
    private final ModelControllerClient controllerClient;

    public ServerDeploymentHelper(ModelControllerClient client) {
        deploymentManager = ServerDeploymentManager.Factory.create(client);
        controllerClient = client;
    }

    public String deploy(String runtimeName, InputStream input) throws ServerDeploymentException {
        return this.deploy(runtimeName, input, new HashMap<String, Object>());
    }

    public String deploy(String runtimeName, InputStream input, Map<String, Object> userdata) throws ServerDeploymentException {
        return this.deploy(runtimeName, input, userdata, true);
    }

    public String deploy(String runtimeName, InputStream input, boolean start) throws ServerDeploymentException {
        return this.deploy(runtimeName, input, new HashMap<String, Object>(), start);
    }

    public String deploy(String runtimeName, InputStream input, Map<String, Object> userdata, boolean start) throws ServerDeploymentException {
        ServerDeploymentActionResult actionResult;
        try {
            DeploymentPlanBuilder builder = deploymentManager.newDeploymentPlan();
            AddDeploymentPlanBuilder addBuilder = builder.add(runtimeName, input); // .addMetadata(userdata);
            if (start == false) {
                // addBuilder = addBuilder.andNoStart();
            }
            builder = addBuilder.andDeploy();
            DeploymentPlan plan = builder.build();
            DeploymentAction action = builder.getLastAction();
            Future<ServerDeploymentPlanResult> future = deploymentManager.execute(plan);
            ServerDeploymentPlanResult planResult = future.get();
            actionResult = planResult.getDeploymentActionResult(action.getId());
        } catch (Exception ex) {
            throw new ServerDeploymentException(ex);
        }
        if (actionResult.getDeploymentException() != null)
            throw new ServerDeploymentException(actionResult);
        return runtimeName;
    }

    public void deploy(String runtimeName, InputStream input, DeploymentOverlay overlay) throws ServerDeploymentException {

        int streamIndex = 0;
        ModelNode op = new ModelNode();
        OperationBuilder builder = new OperationBuilder(op);
        op.get(OP).set(COMPOSITE);
        op.get(OP_ADDR).setEmptyList();
        ModelNode steps = op.get(STEPS);
        steps.setEmptyList();

        ModelNode step = new ModelNode();
        step.get(OP_ADDR).set(DEPLOYMENT_OVERLAY_OPERATION, overlay.getRuntimeName(runtimeName));
        step.get(OP).set(ADD);
        steps.add(step);

        step = new ModelNode();
        ModelNode addr = new ModelNode();
        addr.add(DEPLOYMENT_OVERLAY_OPERATION, overlay.getRuntimeName(runtimeName));
        addr.add(CONTENT, overlay.getPath());
        step.get(OP_ADDR).set(addr);
        step.get(OP).set(ADD);
        ModelNode content = new ModelNode();
        content.get(INPUT_STREAM_INDEX).set(streamIndex++);
        builder.addInputStream(overlay.getContent());
        step.get(CONTENT).set(content);
        steps.add(step);

        step = new ModelNode();
        addr = new ModelNode();
        addr.add(DEPLOYMENT_OVERLAY_OPERATION, overlay.getRuntimeName(runtimeName));
        addr.add(DEPLOYMENT, runtimeName);
        step.get(OP_ADDR).set(addr);
        step.get(OP).set(ADD);
        steps.add(step);

        step = new ModelNode();
        step.get(OP).set(ADD);
        step.get(OP_ADDR).add(DEPLOYMENT, runtimeName);
        step.get(RUNTIME_NAME).set(runtimeName);
        builder.addInputStream(input);
        step.get(CONTENT).get(0).get(INPUT_STREAM_INDEX).set(streamIndex++);
        steps.add(step);

        step = new ModelNode();
        step.get(OP).set(DEPLOYMENT_DEPLOY_OPERATION);
        step.get(OP_ADDR).add(DEPLOYMENT, runtimeName);
        steps.add(step);

        executeCompositeOperation(builder.build(), steps.asList().size());
    }

    public String replace(String runtimeName, String replaceName, InputStream input, boolean removeUndeployed) throws ServerDeploymentException {
        ServerDeploymentActionResult actionResult;
        try {
            DeploymentPlanBuilder builder = deploymentManager.newDeploymentPlan();
            builder = builder.add(runtimeName, input).andReplace(replaceName);
            if (removeUndeployed) {
                builder = ((ReplaceDeploymentPlanBuilder) builder).andRemoveUndeployed();
            }
            DeploymentPlan plan = builder.build();
            DeploymentAction action = builder.getLastAction();
            Future<ServerDeploymentPlanResult> future = deploymentManager.execute(plan);
            ServerDeploymentPlanResult planResult = future.get();
            actionResult = planResult.getDeploymentActionResult(action.getId());
        } catch (Exception ex) {
            throw new ServerDeploymentException(ex);
        }
        if (actionResult.getDeploymentException() != null)
            throw new ServerDeploymentException(actionResult);
        return runtimeName;
    }

    public void undeploy(String runtimeName) throws ServerDeploymentException {
        undeploy(new String[] { runtimeName });
    }

    public void undeploy(String[] runtimeNames) throws ServerDeploymentException {
        ServerDeploymentActionResult actionResult;
        try {
            DeploymentPlanBuilder builder = deploymentManager.newDeploymentPlan();
            for (String runtimeName : runtimeNames) {
                builder = builder.undeploy(runtimeName).remove(runtimeName);
            }
            DeploymentPlan plan = builder.build();
            DeploymentAction action = builder.getLastAction();
            Future<ServerDeploymentPlanResult> future = deploymentManager.execute(plan);
            ServerDeploymentPlanResult planResult = future.get();
            actionResult = planResult.getDeploymentActionResult(action.getId());
        } catch (Exception ex) {
            throw new ServerDeploymentException(ex);
        }
        if (actionResult.getDeploymentException() != null)
            throw new ServerDeploymentException(actionResult);
    }

    public ModelNode executeCompositeOperation(Operation composite, int steps) throws ServerDeploymentException {
        ModelNode resultNode;
        try {
            Future<ModelNode> future = controllerClient.executeAsync(composite, null);
            ModelNode node = future.get();
            resultNode = node.get(ClientConstants.RESULT);
        } catch (Exception ex) {
            throw new ServerDeploymentException(ex);
        }

        // Process the result node
        boolean allgood = true;
        for (int i = 1; i <= steps; i++) {
            ModelNode stepNode = resultNode.get("step-" + i);
            allgood &= SUCCESS.equals(stepNode.get(OUTCOME).asString());
            if (allgood == false) {
                ModelNode descriptionNode = stepNode.get(FAILURE_DESCRIPTION);
                if (descriptionNode.isDefined()) {
                    throw new ServerDeploymentException(descriptionNode.toString());
                }
            }
        }

        // If we get here, there was no step with failure-description
        if (allgood == false) {
            throw new ServerDeploymentException(new ModelNode().toString());
        }
        return resultNode;
    }

    public ModelNode executeOperation(Operation operation) throws ServerDeploymentException {
        ModelNode resultNode;
        try {
            Future<ModelNode> future = controllerClient.executeAsync(operation, null);
            resultNode = future.get();
        } catch (Exception ex) {
            throw new ServerDeploymentException(ex);
        }

        // Process the result node
        if (!SUCCESS.equals(resultNode.get(OUTCOME).asString())) {
            ModelNode descriptionNode = resultNode.get(FAILURE_DESCRIPTION);
            if (descriptionNode.isDefined()) {
                throw new ServerDeploymentException(descriptionNode.toString());
            }
            throw new ServerDeploymentException(new ModelNode().toString());
        }
        return resultNode;
    }

    public static class DeploymentOverlay {
        private final String overlayPath;
        private final InputStream content;

        public DeploymentOverlay(String path, InputStream content) {
            this.overlayPath = path;
            this.content = content;
        }

        public String getRuntimeName(String parentName) {
            return parentName + "/" + overlayPath;
        }

        public String getPath() {
            return overlayPath;
        }

        public InputStream getContent() {
            return content;
        }
    }

    public static class ServerDeploymentException extends Exception {
        private static final long serialVersionUID = 1L;
        private final ServerDeploymentActionResult actionResult;

        public ServerDeploymentException(ServerDeploymentActionResult actionResult) {
            super(actionResult.getDeploymentException());
            this.actionResult = actionResult;
        }

        public ServerDeploymentException(String message) {
            super(message);
            actionResult = null;
        }

        public ServerDeploymentException(Throwable cause) {
            super(cause);
            actionResult = null;
        }

        public ServerDeploymentActionResult getActionResult() {
            return actionResult;
        }
    }
}
