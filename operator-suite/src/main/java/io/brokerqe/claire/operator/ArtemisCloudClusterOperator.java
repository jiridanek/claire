/*
 * Copyright Broker QE authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.brokerqe.claire.operator;

import io.brokerqe.claire.Constants;
import io.brokerqe.claire.EnvironmentOperator;
import io.brokerqe.claire.KubeClient;
import io.brokerqe.claire.ResourceManager;
import io.brokerqe.claire.TestUtils;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class ArtemisCloudClusterOperator {

    final static Logger LOGGER = LoggerFactory.getLogger(ArtemisCloudClusterOperator.class);
    public static final List<String> ZAP_LOG_LEVELS = List.of("debug", "info", "error");
    protected final String deploymentNamespace;
    protected final boolean isNamespaced;
    protected final EnvironmentOperator environmentOperator;
    protected final List<String> watchedNamespaces;
    protected final KubeClient kubeClient;
    protected String operatorName;
    private final String operatorOldNameSuffix = "-operator";
    private final String operatorNewNameSuffix = "-controller-manager";

    public ArtemisCloudClusterOperator(String namespace) {
        this(namespace, true, null);
    }

    public ArtemisCloudClusterOperator(String deploymentNamespace, boolean isNamespaced, List<String> watchedNamespaces) {
        this.deploymentNamespace = deploymentNamespace;
        this.isNamespaced = isNamespaced;
        this.environmentOperator = ResourceManager.getEnvironment();
        this.watchedNamespaces = watchedNamespaces;

        if (environmentOperator.isOlmInstallation()) {
            // try amq-broker-operator or new name
            this.operatorName = operatorNewNameSuffix;
        } else {
            this.operatorName = getOperatorControllerManagerName(ArtemisFileProvider.getOperatorInstallFile());
        }
        this.kubeClient = ResourceManager.getKubeClient().inNamespace(this.deploymentNamespace);
    }

    abstract public void deployOperator(boolean waitForDeployment);

    abstract public void undeployOperator(boolean waitForUndeployment);

    public void waitForCoDeployment() {
        // operator pod/deployment name activemq-artemis-controller-manager vs amq-broker-controller-manager
        TestUtils.waitFor("deployment to be active", Constants.DURATION_5_SECONDS, Constants.DURATION_3_MINUTES,
                () -> kubeClient.getDeployment(deploymentNamespace, getOperatorNewName()) != null ||
                        kubeClient.getDeployment(deploymentNamespace, getOperatorOldName()) != null);

        Deployment deployment = kubeClient.getDeployment(deploymentNamespace, getOperatorNewName());
        if (deployment == null) {
            deployment = kubeClient.getDeployment(deploymentNamespace, getOperatorOldName());
            this.operatorName = getOperatorOldName();
        } else {
            this.operatorName = getOperatorNewName();
        }
        kubeClient.getKubernetesClient().resource(deployment).waitUntilReady(3, TimeUnit.MINUTES);
    }

    public void waitForCoUndeployment() {
        Deployment amqCoDeployment = kubeClient.getDeployment(deploymentNamespace, operatorName);
//        kubeClient.getKubernetesClient().resource(amqCoDeployment).waitUntilCondition(removed, 3, TimeUnit.MINUTES);
        TestUtils.waitFor("ClusterOperator to stop", Constants.DURATION_5_SECONDS, Constants.DURATION_3_MINUTES, () -> {
            return amqCoDeployment == null && kubeClient.listPodsByPrefixName(deploymentNamespace, operatorName).size() == 0;
        });
    }

    public String getDeploymentNamespace() {
        return deploymentNamespace;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public String getOperatorOldName() {
        return environmentOperator.getArtemisOperatorName() + operatorOldNameSuffix;
    }

    public String getOperatorNewName() {
        return environmentOperator.getArtemisOperatorName() + operatorNewNameSuffix;
    }

    public static String getOperatorControllerManagerName(Path yamlFile) {
        Deployment operatorCODeployment = TestUtils.configFromYaml(yamlFile.toFile(), Deployment.class);
        return operatorCODeployment.getMetadata().getName();
    }
}
