package org.springframework.cli.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

/**
 * Manifest for deploying applications to Azure Spring Apps.
 */
public class AzureSpringAppsManifest implements InitializingBean {

    private BaseConfig defaults;

    private List<ApplicationConfig> applications = new ArrayList<>();

    private List<ApplicationConfig> mergedConfigs;

    public AzureSpringAppsManifest() {
    }

    public AzureSpringAppsManifest(BaseConfig defaults, List<ApplicationConfig> applications) {
        this.defaults = defaults;
        this.applications = applications;
    }

    public BaseConfig getDefaults() {
        return defaults;
    }

    public void setDefaults(BaseConfig defaults) {
        this.defaults = defaults;
    }

    public List<ApplicationConfig> getApplications() {
        return applications;
    }

    public void setApplications(List<ApplicationConfig> applications) {
        this.applications = applications;
    }

    public List<ApplicationConfig> getMergedApplicationConfigs() {
        if (mergedConfigs == null) {
            mergeConfigs();
        }
        return mergedConfigs;
    }

    private void mergeConfigs() {
        mergedConfigs = new ArrayList<>(applications);
        if (defaults == null) {
            return;
        }
        mergedConfigs.forEach(conf -> {
            conf.setSubscriptionId(conf.getSubscriptionId() == null ? defaults.getSubscriptionId() : conf.getSubscriptionId());
            conf.setResourceGroup(conf.getResourceGroup() == null ? defaults.getResourceGroup() : conf.getResourceGroup());
            conf.setAzureSpringAppsName(conf.getAzureSpringAppsName() == null ? defaults.getAzureSpringAppsName() : conf.getAzureSpringAppsName());
        });
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        mergeConfigs();
    }

    public static class BaseConfig {
        private String subscriptionId;
        private String resourceGroup;
        private String azureSpringAppsName;

        BaseConfig() {
        }

        public BaseConfig(String subscriptionId, String resourceGroup, String azureSpringAppsName) {
            this.subscriptionId = subscriptionId;
            this.resourceGroup = resourceGroup;
            this.azureSpringAppsName = azureSpringAppsName;
        }

        public BaseConfig(BaseConfig config) {
            this.subscriptionId = config.getSubscriptionId();
            this.resourceGroup = config.getResourceGroup();
            this.azureSpringAppsName = config.getAzureSpringAppsName();
        }

        public String getSubscriptionId() {
            return subscriptionId;
        }

        public void setSubscriptionId(String subscriptionId) {
            this.subscriptionId = subscriptionId;
        }

        public String getResourceGroup() {
            return resourceGroup;
        }

        public void setResourceGroup(String resourceGroup) {
            this.resourceGroup = resourceGroup;
        }

        public String getAzureSpringAppsName() {
            return azureSpringAppsName;
        }

        public void setAzureSpringAppsName(String azureSpringAppsName) {
            this.azureSpringAppsName = azureSpringAppsName;
        }
    }

    public static class ApplicationConfig extends BaseConfig {
        private String name;
        private String memory = "1G";
        private int instances = 1;
        private String path;

        ApplicationConfig() {
        }

        public ApplicationConfig(String subscriptionId, String resourceGroup, String applicationName, String name, String memory, int instances, String path) {
            super(subscriptionId, resourceGroup, applicationName);
            this.name = name;
            this.memory = memory;
            this.instances = instances;
            this.path = path;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getMemory() {
            return memory;
        }

        public void setMemory(String memory) {
            this.memory = memory;
        }

        public int getInstances() {
            return instances;
        }

        public void setInstances(int instances) {
            this.instances = instances;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

}
