/*
 * Copyright Strimzi and Broker QE authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.brokerqe;

import io.brokerqe.operator.ArtemisCloudClusterOperator;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.openshift.client.OpenShiftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class KubeClient {
    protected final KubernetesClient client;
    protected String namespace;

    private static final Logger LOGGER = LoggerFactory.getLogger(KubeClient.class);

    public KubeClient(String namespace) {
        LOGGER.debug("Creating client in namespace: {}", namespace);
        Config config = Config.autoConfigure(System.getenv().getOrDefault("KUBE_CONTEXT", null));

        this.client = new KubernetesClientBuilder()
                .withConfig(config)
                .build()
                .adapt(OpenShiftClient.class);
        this.namespace = namespace;
        LOGGER.info("[{}] Created KubernetesClient: {}.{} - {}", namespace, client.getKubernetesVersion().getMajor(), client.getKubernetesVersion().getMinor(), client.getMasterUrl());
    }

    public KubeClient(KubernetesClient client, String namespaceName) {
        LOGGER.debug("Creating client in namespace: {}", namespaceName);
        this.client = client;
        this.namespace = namespaceName;
    }

    // ============================
    // ---------> CLIENT <---------
    // ============================

    public KubernetesClient getKubernetesClient() {
        return client;
    }

    // ===============================
    // ---------> NAMESPACE <---------
    // ===============================

    public KubeClient inNamespace(String namespaceName) {
        LOGGER.debug("Using namespace: {}", namespaceName);
        this.namespace = namespaceName;
        return this;
    }

    public Namespace createNamespace(String namespaceName) {
        return this.createNamespace(namespaceName, false);
    }
    public Namespace createNamespace(String namespaceName, boolean setNamespace) {
        LOGGER.debug("Creating new namespace: {}", namespaceName);
        Namespace ns = this.getKubernetesClient().resource(new NamespaceBuilder().withNewMetadata().withName(namespaceName).endMetadata().build()).createOrReplace();
        TestUtils.waitFor("Creating namespace", Constants.DURATION_2_SECONDS, Constants.DURATION_3_MINUTES, () -> {
            return this.namespaceExists(namespaceName);
        });
        if (setNamespace) {
            this.namespace = namespaceName;
        }

        return ns;
    }

    public void deleteNamespace(String namespaceName) {
        this.getKubernetesClient().namespaces().withName(namespaceName).delete();
        TestUtils.waitFor("Deleting namespace", Constants.DURATION_2_SECONDS, Constants.DURATION_3_MINUTES, () -> {
            return !this.namespaceExists(namespaceName);
        });
    }

    public String getNamespace() {
        return namespace;
    }

    public Namespace getNamespace(String namespaceName) {
        return client.namespaces().withName(namespaceName).get();
    }

    public boolean namespaceExists(String namespaceName) {
        return client.namespaces().list().getItems().stream().map(n -> n.getMetadata().getName())
            .collect(Collectors.toList()).contains(namespaceName);
    }

    /**
     * Gets namespace status
     */
    public boolean getNamespaceStatus(String namespaceName) {
        return client.namespaces().withName(namespaceName).isReady();
    }

    public long getAvailableUserId(String namespace, long defaultUserId) {
        long userId = defaultUserId;
        try {
            String range = getNamespace(namespace).getMetadata().getAnnotations().get("openshift.io/sa.scc.uid-range");
            // 1001040000/10000
            userId = Long.parseLong(range.split("/")[0]) + defaultUserId;
        } catch (NullPointerException e) {
            LOGGER.debug("[{}] Unable to detect 'openshift.io/sa.scc.uid-range', using default userId {}", namespace, userId);
        }
        return userId;
    }

    // ================================
    // ---------> CONFIG MAP <---------
    // ================================
    public ConfigMap getConfigMap(String namespaceName, String configMapName) {
        return client.configMaps().inNamespace(namespaceName).withName(configMapName).get();
    }

    public ConfigMap getConfigMap(String configMapName) {
        return getConfigMap(namespace, configMapName);
    }


    public boolean getConfigMapStatus(String configMapName) {
        return client.configMaps().inNamespace(getNamespace()).withName(configMapName).isReady();
    }

    // =========================
    // ---------> POD <---------
    // =========================
    public List<Pod> listPods() {
        return client.pods().inNamespace(namespace).list().getItems();
    }

    public List<Pod> listPods(String namespaceName) {
        return client.pods().inNamespace(namespaceName).list().getItems();
    }

    /**
     * Returns list of pods by prefix in pod name
     * @param namespaceName Namespace name
     * @param podNamePrefix prefix with which the name should begin
     * @return List of pods
     */
    public List<Pod> listPodsByPrefixInName(String namespaceName, String podNamePrefix) {
        return listPods(namespaceName)
                .stream().filter(p -> p.getMetadata().getName().startsWith(podNamePrefix))
                .collect(Collectors.toList());
    }

    /**
     * Gets pod
     */
    public Pod getPod(String namespaceName, String name) {
        return client.pods().inNamespace(namespaceName).withName(name).get();
    }

    public Pod getPod(String name) {
        return getPod(namespace, name);
    }

    public Pod getFirstPodByPrefixName(String namespaceName, String podNamePrefix) {
        List<Pod> pods = listPodsByPrefixInName(namespaceName, podNamePrefix);
        if (pods.size() > 1) {
            LOGGER.warn("[{}] Returning first found pod with name '{}' of many ({})!", namespaceName, podNamePrefix, pods.size());
            return pods.get(0);
        } else if (pods.size() > 0) {
            return pods.get(0);
        } else {
            return null;
        }
    }

    public void reloadPodWithWait(String namespaceName, Pod pod, String podName) {
        this.getKubernetesClient().resource(pod).inNamespace(namespaceName).delete();
        waitForPodReload(namespaceName, pod, podName);
    }

    public void waitUntilPodIsReady(String namespaceName, String podName) {
        client.pods().inNamespace(namespaceName).withName(podName).waitUntilReady(3, TimeUnit.MINUTES);
    }

    public void waitForPodReload(String namespace, Pod pod, String podName) {
        waitForPodReload(namespace, pod, podName, Constants.DURATION_1_MINUTE);
    }

    public void waitForPodReload(String namespace, Pod pod, String podName, long maxTimeout) {
        String originalUid = pod.getMetadata().getUid();

        LOGGER.info("Waiting for pod {} reload in namespace {}", podName, namespace);

        TestUtils.waitFor("Pod to be reloaded and ready", Constants.DURATION_5_SECONDS, maxTimeout, () -> {
            Pod newPod = this.getFirstPodByPrefixName(namespace, podName);
            return newPod != null && !newPod.getMetadata().getUid().equals(originalUid);
        });
        this.waitUntilPodIsReady(namespace, this.getFirstPodByPrefixName(namespace, podName).getMetadata().getName());
    }

    // ==================================
    // ---------> STATEFUL SET <---------
    // ==================================

    /**
     * Gets stateful set
     */
    public StatefulSet getStatefulSet(String namespaceName, String statefulSetName) {
        return client.apps().statefulSets().inNamespace(namespaceName).withName(statefulSetName).get();
    }

    public StatefulSet getStatefulSet(String statefulSetName) {
        return getStatefulSet(namespace, statefulSetName);
    }

    /**
     * Gets stateful set
     */
    public RollableScalableResource<StatefulSet> statefulSet(String namespaceName, String statefulSetName) {
        return client.apps().statefulSets().inNamespace(namespaceName).withName(statefulSetName);
    }

    public RollableScalableResource<StatefulSet> statefulSet(String statefulSetName) {
        return statefulSet(namespace, statefulSetName);
    }
    // ================================
    // ---------> DEPLOYMENT <---------
    // ================================

    /**
     * Gets deployment
     */

    public Deployment getDeployment(String namespaceName, String deploymentName) {
        return client.apps().deployments().inNamespace(namespaceName).withName(deploymentName).get();
    }

    public Deployment getDeployment(String deploymentName) {
        return client.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
    }

    public Deployment getDeploymentFromAnyNamespaces(String deploymentName) {
        return client.apps().deployments().inAnyNamespace().list().getItems().stream().filter(
            deployment -> deployment.getMetadata().getName().equals(deploymentName))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Gets deployment status
     */
    public LabelSelector getDeploymentSelectors(String namespaceName, String deploymentName) {
        return client.apps().deployments().inNamespace(namespaceName).withName(deploymentName).get().getSpec().getSelector();
    }

    // ==============================
    // ---------> SERVICES <---------
    // ==============================

    public Service getServiceByNames(String namespaceName, String serviceName) {
        return client.services().inNamespace(namespaceName).withName(serviceName).get();
    }

    public Service getServiceBrokerAcceptor(String namespaceName, String brokerName, String acceptorName) {
        return client.services().inNamespace(namespaceName).list().getItems().stream()
                .filter(svc -> svc.getMetadata().getName().startsWith(brokerName + "-" + acceptorName)
                ).findFirst().get();
    }

    // ==========================
    // ---------> NODE <---------
    // ==========================

    public String getNodeAddress() {
        return listNodes().get(0).getStatus().getAddresses().get(0).getAddress();
    }

    public List<Node> listNodes() {
        return client.nodes().list().getItems();
    }

    public List<Node> listWorkerNodes() {
        return listNodes().stream().filter(node -> node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/worker")).collect(Collectors.toList());
    }

    public List<Node> listMasterNodes() {
        return listNodes().stream().filter(node -> node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/master")).collect(Collectors.toList());
    }

    // =========================
    // ---------> JOB <---------
    // =========================

    public boolean jobExists(String jobName) {
        return client.batch().v1().jobs().inNamespace(namespace).list().getItems().stream().anyMatch(j -> j.getMetadata().getName().startsWith(jobName));
    }

    public Job getJob(String jobName) {
        return client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get();
    }

    public boolean checkSucceededJobStatus(String jobName) {
        return checkSucceededJobStatus(getNamespace(), jobName, 1);
    }

    public boolean checkSucceededJobStatus(String namespaceName, String jobName, int expectedSucceededPods) {
        return getJobStatus(namespaceName, jobName).getSucceeded().equals(expectedSucceededPods);
    }

    public boolean checkFailedJobStatus(String namespaceName, String jobName, int expectedFailedPods) {
        return getJobStatus(namespaceName, jobName).getFailed().equals(expectedFailedPods);
    }

    // Pods Statuses:  0 Running / 0 Succeeded / 1 Failed
    public JobStatus getJobStatus(String namespaceName, String jobName) {
        return client.batch().v1().jobs().inNamespace(namespaceName).withName(jobName).get().getStatus();
    }

    public JobStatus getJobStatus(String jobName) {
        return getJobStatus(namespace, jobName);
    }

    public JobList getJobList() {
        return client.batch().v1().jobs().inNamespace(namespace).list();
    }

    public List<Job> listJobs(String namePrefix) {
        return client.batch().v1().jobs().inNamespace(getNamespace()).list().getItems().stream()
            .filter(job -> job.getMetadata().getName().startsWith(namePrefix)).collect(Collectors.toList());
    }


    /*******************************************************************************************************************
     *  Deploy ActiveMQ Artemis Operator
     ******************************************************************************************************************/
    public ArtemisCloudClusterOperator deployClusterOperator(String namespace) {
        ArtemisCloudClusterOperator clusterOperator = ResourceManager.deployArtemisClusterOperator(namespace);
        return clusterOperator;
    }

    public ArtemisCloudClusterOperator deployClusterOperator(String namespace, List<String> watchedNamespaces) {
        ArtemisCloudClusterOperator clusterOperator = ResourceManager.deployArtemisClusterOperatorClustered(namespace, watchedNamespaces);
        return clusterOperator;
    }

    public void undeployClusterOperator(ArtemisCloudClusterOperator operator) {
        ResourceManager.undeployArtemisClusterOperator(operator);
    }

}
