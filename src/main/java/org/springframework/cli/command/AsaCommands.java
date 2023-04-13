package org.springframework.cli.command;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.appplatform.models.DeploymentInstance;
import com.azure.resourcemanager.appplatform.models.RuntimeVersion;
import com.azure.resourcemanager.appplatform.models.SpringApp;
import com.azure.resourcemanager.appplatform.models.SpringService;
import org.apache.maven.model.Model;
import org.jetbrains.annotations.NotNull;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.cli.SpringCliException;
import org.springframework.cli.config.AzureSpringAppsManifest;
import org.springframework.cli.support.configfile.ConfigFile;
import org.springframework.cli.support.configfile.YamlConfigFile;
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

    private static final String DEFAULT_DEPLOYMENT_NAME = "default";
    private static final int DEFAULT_INSTANCE = 1;
    private static final double DEFAULT_MEMORY_SIZE_GB = 2;

    private static final BigDecimal D_1024 = BigDecimal.valueOf(1024);

    private final ComponentFlow.Builder componentFlowBuilder;
    private final TerminalMessage terminalMessage;

    @Autowired
    AsaCommands(ComponentFlow.Builder componentFlowBuilder, TerminalMessage terminalMessage) {
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
        RuntimeVersion javaVersion = resolveJavaVersion(model);

        if (model.getModules().size() > 1) {
            Assert.state(manifestExists(workingPath), "Cannot find manifest.yml in the current directory.");
            AzureSpringAppsManifest manifest = resolveAzureSpringAppsManifest(workingPath.resolve("manifest.yml"));
            deployFromManifest(manifest, javaVersion);
        } else {
            if (manifestExists(workingPath)) {
                AzureSpringAppsManifest manifest = resolveAzureSpringAppsManifest(workingPath.resolve("manifest.yml"));
                deployFromManifest(manifest, javaVersion);
            } else {
                String defaultAppName = resolveApplicationName(workingPath.resolve("src/main/resources"), model.getArtifactId());
                String artifactPath = resolveArtifactPath(model);
                File targetJarFile = resolveTargetJarFile(artifactPath);

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

                deployApplication(context.get(SUBSCRIPTION_ID_ID),
                    context.get(RESOURCE_GROUP_ID),
                    context.get(ASA_SERVICE_NAME_ID),
                    context.get(ASA_APPLICATION_NAME_ID),
                    DEFAULT_INSTANCE,
                    DEFAULT_MEMORY_SIZE_GB,
                    javaVersion,
                    targetJarFile);
            }
        }

    }

    private void deployFromManifest(AzureSpringAppsManifest manifest, RuntimeVersion javaVersion) {
        terminalMessage.print("Reading manifest");
        List<AzureSpringAppsManifest.ApplicationConfig> applicationConfigs = manifest.getMergedApplicationConfigs();
        applicationConfigs.forEach(conf -> {
            try {
                deployApplication(conf.getSubscriptionId(),
                    conf.getResourceGroup(),
                    conf.getAzureSpringAppsName(),
                    conf.getName(),
                    conf.getInstances(),
                    resolveMemorySize(conf.getMemory()),
                    javaVersion,
                    new File(conf.getPath()));
            }
            catch (IOException e) {
                throw new SpringCliException("Error while deploying from the manifest", e);
            }
        });
    }

    private void deployApplication(String subscriptionId, String resourceGroup, String serviceName, String appName, int instances, double memory, RuntimeVersion javaVersion, File targetJarFile) throws IOException {
        AzureProfile profile = buildProfile(AzureEnvironment.AZURE);
        TokenCredential credential = buildCredential(profile);
        AzureResourceManager azureResourceManager = buildResourceManager(profile, credential, subscriptionId);

        SpringService springService = getSpringService(azureResourceManager, resourceGroup, serviceName);
        Assert.notNull(springService, String.format("Cannot find the Azure Spring Apps %s in resource group %s", serviceName, resourceGroup));

        terminalMessage.print(String.format("Creating Azure Spring Apps application %s and deployment with the following information: %n"
                + "Subscription: %s %n"
                + "Resource group: %s %n"
                + "ASA service: %s %n"
                + "Instance number: %d %n"
                + "Memory: %f GB %n"
                + "Runtime version: %s %n"
                + "Jar file path: %s.",
            appName, subscriptionId, resourceGroup, serviceName, instances, memory, javaVersion.toString(), targetJarFile.getAbsolutePath()));

        SpringApp app = createSpringApp(springService, appName, DEFAULT_DEPLOYMENT_NAME, instances, memory, javaVersion, targetJarFile)
            .block();

        terminalMessage.print(String.format("Azure Spring Apps application %s has been successfully created and deployed.", appName));
        String url = app.url();
        terminalMessage.print(String.format("The public endpoint: " + url));

        Disposable disposable = streamLogs(springService, appName, DEFAULT_DEPLOYMENT_NAME, 300, 500, 1024 * 1024, false)
            .doFirst(() -> terminalMessage.print("###############STREAMING LOG BEGIN##################"))
            .doFinally(type -> terminalMessage.print("###############STREAMING LOG END##################"))
            .subscribe(s -> terminalMessage.print(s));
        if (!disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private Mono<SpringApp> createSpringApp(SpringService springService, String appName, String deploymentName, int instances, double memory, RuntimeVersion javaVersion, File targetJarFile) {
        return springService.apps().define(appName)
            .defineActiveDeployment(deploymentName)
            .withJarFile(targetJarFile)
            .withRuntime(javaVersion)
            .withMemory(memory)
            .withInstance(instances)
            .attach()
            .withDefaultPublicEndpoint()
            .createAsync();
    }

    private Flux<String> streamLogs(SpringService springService, String applicationName, String deploymentName, int sinceSeconds, int tailLines, int limitBytes, boolean follow) throws IOException {
        SpringApp app = springService.apps()
            .getByName(applicationName);
        DeploymentInstance latestInstance = getLatestInstance(app, deploymentName);
        String testEndpoint = springService.listTestKeys().primaryTestEndpoint();
        String logStreamingEndpoint = getLogStreamingEndpoint(testEndpoint, applicationName, latestInstance.name());


        final URL url = new URL(String.format("%s?tailLines=%s&follow=%s&sinceSeconds=%s&limitBytes=%s", logStreamingEndpoint, tailLines, follow, sinceSeconds, limitBytes));
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        final String password = springService.listTestKeys().primaryKey();
        final String userPass = "primary:" + password;
        final String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userPass.getBytes()));
        connection.setRequestProperty("Authorization", basicAuth);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        return Flux.create((fluxSink) -> {
            try {
                final InputStream is = connection.getInputStream();
                BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = rd.readLine()) != null) {
                    fluxSink.next(line);
                }
                rd.close();
            } catch (final Exception e) {
                throw new SpringCliException("Error while requesting log streaming for app " + applicationName, e);
            }
        });
    }

    private String getLogStreamingEndpoint(String testEndpoint, String applicationName, String instanceName) {
        return String.format("%s/api/logstream/apps/%s/instances/%s", testEndpoint.replace(".test", ""), applicationName, instanceName);
    }

    private DeploymentInstance getLatestInstance(SpringApp application, String deploymentName) {
        List<DeploymentInstance> instanceList = application
            .deployments().getByName(deploymentName).instances();
        return instanceList.stream().max(Comparator.comparing(instance -> instance.startTime()))
            .orElseThrow(() -> new IllegalStateException("No instance in the deployment " + deploymentName));
    }

    private boolean manifestExists(Path workingPath) {
        return workingPath.resolve("manifest.yml").toFile().exists();
    }

    private AzureSpringAppsManifest resolveAzureSpringAppsManifest(Path manifestPath) {
        ConfigFile configFile = new YamlConfigFile();
        return configFile.read(manifestPath, AzureSpringAppsManifest.class);
    }

    private double resolveMemorySize(String memorySize) {
        String unit = memorySize.substring(memorySize.length() - 1);
        BigDecimal size = BigDecimal.valueOf(Integer.valueOf(memorySize.substring(0, memorySize.length() - 1)));
        switch (unit.toUpperCase(Locale.ROOT)) {
        case "G" :
            return size.doubleValue();
        case "M" :
            return size.divide(D_1024, 2, RoundingMode.HALF_UP).doubleValue();
        default:
            throw new IllegalArgumentException(String.format("The memorySize %s is not illegal", memorySize));
        }
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

    private File resolveTargetJarFile(String artifactPath) {
        File targetJarFile = new File(artifactPath);
        Assert.isTrue(targetJarFile.exists(), String.format("No existing jar file found in %s.", artifactPath));
        return targetJarFile;
    }

    String resolveApplicationName(Path resourcesPath, String artifactId) throws IOException {
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
                FileSystemResource fileResource = new FileSystemResource(applicationYaml);
                YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
                yamlFactory.setSingleton(false);
                yamlFactory.setResources(fileResource);
                Properties properties = yamlFactory.getObject();
                return (String) properties.get(key);
            });
    }
}
