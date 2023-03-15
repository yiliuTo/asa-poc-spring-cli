package org.springframework.cli.command;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.appplatform.models.RuntimeVersion;
import com.azure.resourcemanager.appplatform.models.SpringService;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.DirectoryScanner;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.beans.factory.config.YamlProcessor;
import org.springframework.cli.config.SpringCliUserConfig;
import org.springframework.cli.util.IoUtils;
import org.springframework.cli.util.PomReader;
import org.springframework.cli.util.TerminalMessage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.shell.component.context.ComponentContext;
import org.springframework.shell.component.flow.ComponentFlow;
import org.springframework.shell.component.flow.ResultMode;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.util.Assert;

import static com.azure.resourcemanager.appplatform.models.RuntimeVersion.JAVA_11;
import static com.azure.resourcemanager.appplatform.models.RuntimeVersion.JAVA_17;
import static com.azure.resourcemanager.appplatform.models.RuntimeVersion.JAVA_8;

/**
 * Commands for Azure Spring Apps
 */
@ShellComponent
public class AsaCommands extends AbstractSpringCliCommands {

    private static final String ARTIFACT_PATH_FORMAT = "target/%s-%s.%s";

    private static final String SPRING_APPLICATION_NAME_PROPERTY = "spring.application.name";

    private static final String SUBSCRIPTION_ID_ID = "subscriptionId";
    private static final String SUBSCRIPTION_ID_NAME = "What's your subscription id?";
    private static final String RESOURCE_GROUP_ID = "resourceGroup";
    private static final String RESOURCE_GROUP_NAME = "What's your resource group name?";
    private static final String ASA_SERVICE_NAME_ID = "serviceName";
    private static final String ASA_SERVICE_NAME_NAME = "What's your Azure Spring Apps service instance name?";
    private static final String ASA_APPLICATION_NAME_ID = "applicationName";
    private static final String ASA_APPLICATION_NAME_NAME = "What's your Azure Spring Apps application name?";

    private final SpringCliUserConfig springCliUserConfig;
    private final ComponentFlow.Builder componentFlowBuilder;
    private final TerminalMessage terminalMessage;

    @Autowired
    AsaCommands(SpringCliUserConfig springCliUserConfig, ComponentFlow.Builder componentFlowBuilder, TerminalMessage terminalMessage) {
        this.springCliUserConfig = springCliUserConfig;
        this.componentFlowBuilder = componentFlowBuilder;
        this.terminalMessage = terminalMessage;
    }

    @ShellMethod(key = "asa deploy", value = "Deploy the target file to Azure Spring Apps")
    public void deploy(
        @ShellOption(help = "Subscription id", defaultValue = ShellOption.NULL) String subscriptionId,
        @ShellOption(help = "Resource group name", defaultValue = ShellOption.NULL) String resourceGroup,
        @ShellOption(help = "ASA service name", defaultValue = ShellOption.NULL) String serviceName,
        @ShellOption(help = "ASA app name", defaultValue = ShellOption.NULL) String appName
    ) throws IOException {
        Path workingPath = IoUtils.getWorkingDirectory();
        Model model = getModel(workingPath);
        String artifactPath = resolveArtifactPath(model);
        RuntimeVersion javaVersion = resolveJavaVersion(model);
        String defaultAppName = resolveApplicationName(workingPath.resolve("src/main/resources"), model.getArtifactId());
        File targetJarFile = new File(artifactPath);
        Assert.isTrue(targetJarFile.exists(), String.format("No existing jar file found in %s.", artifactPath));

        ComponentFlow wizard = componentFlowBuilder.clone().reset()
            .withStringInput(SUBSCRIPTION_ID_ID)
                .name(SUBSCRIPTION_ID_NAME)
                .resultValue(subscriptionId)
                .resultMode(ResultMode.ACCEPT)
                .and()
            .withStringInput(RESOURCE_GROUP_ID)
                .name(RESOURCE_GROUP_NAME)
                .resultValue(resourceGroup)
                .resultMode(ResultMode.ACCEPT)
                .and()
            .withStringInput(ASA_SERVICE_NAME_ID)
                .name(ASA_SERVICE_NAME_NAME)
                .resultValue(serviceName)
                .resultMode(ResultMode.ACCEPT)
                .and()
            .withStringInput(ASA_APPLICATION_NAME_ID)
                .name(ASA_APPLICATION_NAME_NAME)
                .defaultValue(defaultAppName)
                .resultValue(appName)
                .resultMode(ResultMode.ACCEPT)
                .and()
            .build();

        ComponentFlow.ComponentFlowResult result = wizard.run();
        ComponentContext<?> context = result.getContext();

        AzureProfile profile = buildProfile(AzureEnvironment.AZURE);
        TokenCredential credential = buildCredential(profile);
        AzureResourceManager azureResourceManager = buildResourceManager(profile, credential, context.get(SUBSCRIPTION_ID_ID));

        SpringService springAppsArm = getSpringService(azureResourceManager, context.get(RESOURCE_GROUP_ID), context.get(ASA_SERVICE_NAME_ID));
        Assert.notNull(springAppsArm, String.format("Cannot find the Azure Spring Apps %s in resource group %s", context.get(ASA_SERVICE_NAME_ID), context.get(RESOURCE_GROUP_ID)));

        terminalMessage.print("Creating Azure Spring Apps application " + context.get(ASA_APPLICATION_NAME_ID));
        springAppsArm.apps().define(context.get(ASA_APPLICATION_NAME_ID))
            .defineActiveDeployment("default")
            .withJarFile(targetJarFile)
            .withRuntime(javaVersion)
            .attach()
            .withDefaultPublicEndpoint()
            .create();
        terminalMessage.print("Created Azure Spring Apps application " + context.get(ASA_APPLICATION_NAME_ID));
    }

    private SpringService getSpringService(AzureResourceManager azureResourceManager, String resourceGroup, String springService) {
        return azureResourceManager.springServices()
            .getByResourceGroup(resourceGroup, springService);
    }

    private AzureResourceManager buildResourceManager(AzureProfile profile, TokenCredential credential, String subscriptionId) {
        return AzureResourceManager
            .configure()
            .withLogLevel(HttpLogDetailLevel.BASIC)
            .authenticate(credential, profile)
            .withSubscription(subscriptionId);
    }

    private DefaultAzureCredential buildCredential(AzureProfile profile) {
        return new DefaultAzureCredentialBuilder()
            .authorityHost(profile.getEnvironment().getActiveDirectoryEndpoint())
            .build();
    }

    @NotNull
    private AzureProfile buildProfile(AzureEnvironment environment) {
        return new AzureProfile(environment);
    }

    private Model getModel(Path workingPath) {
        File pom = workingPath.resolve("pom.xml").toFile();
        Assert.state(pom.exists(), "pom.xml does not exist.");
        PomReader pomReader = new PomReader();
        Model model = pomReader.readPom(pom);
        return model;
    }

    private String resolveArtifactPath(Model model) {
        return String.format(ARTIFACT_PATH_FORMAT, model.getArtifactId(), model.getVersion(), model.getPackaging());
    }

    private RuntimeVersion resolveJavaVersion(Model model) {
        Assert.isTrue(model.getProperties().containsKey("java.version"), "Java version is not configured in pom.xml");
        String javaVersion = model.getProperties().getProperty("java.version");
        switch (javaVersion) {
            case "1.8":
                return JAVA_8;
            case "11":
                return JAVA_11;
            case "17":
                return JAVA_17;
            default:
                throw new IllegalArgumentException(String.format("Java version %s is not supported.", javaVersion));
        }
    }

    private String resolveApplicationName(Path resourcesPath, String artifactId) throws IOException {
        return resolveValueFromConfiguration(resourcesPath, SPRING_APPLICATION_NAME_PROPERTY)
            .orElse(artifactId);
    }

    private Optional<String> resolveValueFromConfiguration(Path resourcesPath,String key) throws IOException {
        return getValueFromPropertiesFile(resourcesPath.resolve("application.properties").toFile(), key)
            .or(() -> getValueFromYamlFile(resourcesPath.resolve("application.yaml").toFile(), key))
            .or(() -> getValueFromYamlFile(resourcesPath.resolve("application.yml").toFile(), key));
    }

    private Optional<String> getValueFromPropertiesFile(File applicationProperties, String key) {
        return Optional.of(applicationProperties)
                    .filter(file -> file.exists())
                    .map(file -> {
                        Properties properties = new Properties();
                        try {
                            properties.load(new FileInputStream(applicationProperties));
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return properties.getProperty(key);
                    });
    }

    private Optional<String> getValueFromYamlFile(File applicationYaml, String key) {
        return Optional.of(applicationYaml)
            .filter(file -> applicationYaml.exists())
            .map(file -> {
                YamlMapFactoryBean factory = new YamlMapFactoryBean();
                factory.setResolutionMethod(YamlProcessor.ResolutionMethod.OVERRIDE_AND_IGNORE);
                FileSystemResource fileResource = new FileSystemResource(applicationYaml);
                factory.setResources(fileResource);
                Map<String, Object> yamlAsMap = factory.getObject();
                return (String) yamlAsMap.get(key);
            });
    }
}
