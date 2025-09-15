package io.jenkins.plugins.projectenv;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import io.jenkins.plugins.projectenv.agent.AgentInfo;
import io.jenkins.plugins.projectenv.agent.AgentInfoCallable;
import io.jenkins.plugins.projectenv.agent.OperatingSystem;
import io.jenkins.plugins.projectenv.context.StepContextHelper;
import io.jenkins.plugins.projectenv.proc.ProcHelper;
import io.jenkins.plugins.projectenv.proc.ProcResult;
import io.jenkins.plugins.projectenv.toolinfo.ToolInfo;
import io.jenkins.plugins.projectenv.toolinfo.ToolInfoParser;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WithProjectEnvStepExecution extends GeneralNonBlockingStepExecution {

    private static final Pattern LATEST_CLI_VERSION_PATTERN = Pattern.compile(".+/v(.+)$");

    private static final String PROJECT_ENV_CLI_DOWNLOAD_PATTERN = "https://github.com/Project-Env/project-env-core/releases/download/v{0}/cli-{0}-{1}-{2}.{3}";

    private static final String CLI_ARCHIVE_EXTENSION_TAR_GZ = "tar.gz";
    private static final String CLI_ARCHIVE_EXTENSION_ZIP = "zip";

    private static final String CLI_EXECUTABLE_FILE_NAME = "project-env-cli";

    private static final String CLI_EXECUTABLE_FILE_EXTENSION_WINDOWS = ".exe";
    private static final String CLI_EXECUTABLE_FILE_EXTENSION_OTHERS = StringUtils.EMPTY;

    private static final String CLI_TARGET_OS_WINDOWS = "windows";
    private static final String CLI_TARGET_OS_MACOS = "macos";
    private static final String CLI_TARGET_OS_LINUX = "linux";

    private static final String CLI_TARGET_ARCH_AMD_64 = "amd64";
    private static final String CLI_TARGET_ARCH_AARCH64 = "aarch64";

    private static final String PATH_VAR_PREFIX = "PATH+";

    private static final String PROJECT_ENV_MAVEN_TOOL_NAME = "maven";
    private static final String PROJECT_ENV_MAVEN_USER_SETTINGS_FILE = "userSettingsFile";

    private final String fixedCliVersion;
    private final boolean cliDebug;
    private final String configFile;
    private final boolean skipCleanup;

    public WithProjectEnvStepExecution(StepContext stepContext, boolean cliDebug, String configFile, String fixedCliVersion, boolean skipCleanup) {
        super(stepContext);

        this.fixedCliVersion = fixedCliVersion;
        this.cliDebug = cliDebug;
        this.configFile = configFile;
        this.skipCleanup = skipCleanup;
    }

    @Override
    public boolean start() {
        run(this::execute);
        return false;
    }

    private void execute() throws Exception {
        AgentInfo agentInfo = getAgentInfo();

        FilePath temporaryDirectory = createTemporaryDirectory();
        EnvVars projectEnvVars = new EnvVars();

        String executable = resolveProjectEnvCliExecutableFromPath(agentInfo);
        if (executable == null) {
            FilePath projectEnvCliArchive = downloadProjectEnvCliArchive(agentInfo, temporaryDirectory);
            extractProjectEnvCliArchive(projectEnvCliArchive, temporaryDirectory);

            executable = resolveProjectEnvCliExecutable(agentInfo, temporaryDirectory);
            projectEnvVars.put(PATH_VAR_PREFIX + "PROJECT_ENV_CLI", temporaryDirectory.getRemote());
        }
        Map<String, List<ToolInfo>> allToolInfos = executeProjectEnvCli(executable);
        processToolInfos(projectEnvVars, allToolInfos, agentInfo);

        BodyExecutionCallback callback = createTempDirectoryCleanupCallback(temporaryDirectory);
        invokeBodyWithEnvVarsAndCallback(projectEnvVars, callback);
    }

    private String resolveProjectEnvCliExecutableFromPath(AgentInfo agentInfo) throws Exception {
        String[] commands = getExecutablePathResolveCommand(agentInfo);

        String stdOut = StringUtils.trimToNull(ProcHelper.executeAndGetStdOut(getContext(), commands));
        if (stdOut == null) {
            return null;
        }

        return stdOut.split(agentInfo.lineSeparator())[0];
    }

    private String[] getExecutablePathResolveCommand(AgentInfo agentInfo) {
        String executable = getProjectEnvCliExecutableName(agentInfo);
        if (agentInfo.operatingSystem() == OperatingSystem.WINDOWS) {
            return new String[]{"where", executable};
        } else {
            return new String[]{"/bin/sh", "-c", "which " + executable};
        }
    }

    private AgentInfo getAgentInfo() throws Exception {
        return StepContextHelper.getComputer(getContext()).getChannel().call(new AgentInfoCallable());
    }

    private FilePath createTemporaryDirectory() throws Exception {
        FilePath temporaryDirectoryRoot = StepContextHelper.getTemporaryDirectory(getContext());
        String temporaryDirectoryName = generateTemporaryDirectoryName();

        return temporaryDirectoryRoot.child(temporaryDirectoryName);
    }

    private String generateTemporaryDirectoryName() {
        return "withProjectEnv" + Util.getDigestOf(UUID.randomUUID().toString()).substring(0, 8);
    }

    private FilePath downloadProjectEnvCliArchive(AgentInfo agentInfo, FilePath targetDirectory) throws Exception {
        String archiveUrl = createProjectEnvCliArchiveUrl(agentInfo);
        String archiveFilename = FilenameUtils.getName(archiveUrl);

        FilePath targetFile = targetDirectory.child(archiveFilename);
        targetFile.copyFrom(new URI(archiveUrl).toURL());

        return targetFile;
    }

    private String createProjectEnvCliArchiveUrl(AgentInfo agentInfo) {
        String cliTargetOs = getCliTargetOs(agentInfo);
        String cliArchiveExtension = getCliArchiveExtension(agentInfo);
        String cliTargetArchitecture = getCliTargetArchitecture(agentInfo);

        return MessageFormat.format(PROJECT_ENV_CLI_DOWNLOAD_PATTERN, getCliVersion(), cliTargetOs, cliTargetArchitecture, cliArchiveExtension);
    }

    private String getCliVersion() {
        if (fixedCliVersion != null) {
            return fixedCliVersion;
        }

        return resolveLatestCliVersion();
    }

    private String resolveLatestCliVersion() {
        try {
            try (CloseableHttpClient httpClient = HttpClients.custom().disableRedirectHandling().build()) {
                HttpUriRequest request = RequestBuilder.get().setUri("https://github.com/Project-Env/project-env-cli/releases/latest").build();
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    if (statusCode != 302) {
                        throw new IllegalStateException("expected redirection, but got " + statusCode);
                    }

                    Header location = response.getFirstHeader("Location");
                    if (location == null) {
                        throw new IllegalStateException("no redirection location present");
                    }

                    Matcher matcher = LATEST_CLI_VERSION_PATTERN.matcher(location.getValue());
                    if (!matcher.find()) {
                        throw new IllegalStateException("failed to extract latest Project-Env CLI version from URL " + location.getValue());
                    }

                    return matcher.group(1);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to resolve latest Project-Env CLI version", e);
        }
    }

    private String getCliTargetOs(AgentInfo agentInfo) {
        OperatingSystem operatingSystem = agentInfo.operatingSystem();
        return switch (operatingSystem) {
            case WINDOWS -> CLI_TARGET_OS_WINDOWS;
            case MACOS -> CLI_TARGET_OS_MACOS;
            case LINUX -> CLI_TARGET_OS_LINUX;
        };
    }

    private String getCliArchiveExtension(AgentInfo agentInfo) {
        OperatingSystem operatingSystem = agentInfo.operatingSystem();
        return switch (operatingSystem) {
            case WINDOWS -> CLI_ARCHIVE_EXTENSION_ZIP;
            case MACOS, LINUX -> CLI_ARCHIVE_EXTENSION_TAR_GZ;
        };
    }

    private String getCliTargetArchitecture(AgentInfo agentInfo) {
        var architecture = agentInfo.architecture();
        return switch (architecture) {
            case AMD64 -> CLI_TARGET_ARCH_AMD_64;
            case AARCH64 -> CLI_TARGET_ARCH_AARCH64;
        };
    }

    private void extractProjectEnvCliArchive(FilePath archive, FilePath target) throws Exception {
        if (StringUtils.endsWith(archive.getName(), CLI_ARCHIVE_EXTENSION_TAR_GZ)) {
            archive.untar(target, FilePath.TarCompression.GZIP);
        } else {
            archive.unzip(target);
        }
    }

    private String resolveProjectEnvCliExecutable(AgentInfo agentInfo, FilePath sourceDirectory) throws Exception {
        String executableFilename = getProjectEnvCliExecutableName(agentInfo);
        FilePath executable = sourceDirectory.child(executableFilename);
        if (!executable.exists()) {
            throw new IllegalStateException("could not find Project-Env CLI at " + executable);
        }

        return executable.getRemote();
    }

    private String getProjectEnvCliExecutableName(AgentInfo agentInfo) {
        return CLI_EXECUTABLE_FILE_NAME + getExecutableExtension(agentInfo);
    }

    private String getExecutableExtension(AgentInfo agentInfo) {
        return agentInfo.operatingSystem() == OperatingSystem.WINDOWS ?
                CLI_EXECUTABLE_FILE_EXTENSION_WINDOWS : CLI_EXECUTABLE_FILE_EXTENSION_OTHERS;
    }

    private Map<String, List<ToolInfo>> executeProjectEnvCli(String executable) throws Exception {
        String[] commands = createProjectEnvCliCommand(executable);
        ProcResult procResult = ProcHelper.execute(getContext(), commands);
        if (procResult.exitCode() != 0) {
            throw new IllegalStateException("received non-zero exit code " + procResult.exitCode() + " from Project-Env CLI");
        }

        return ToolInfoParser.fromJson(procResult.stdOutput());
    }

    private String[] createProjectEnvCliCommand(String executable) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--config-file=" + configFile);
        if (cliDebug) {
            command.add("--debug");
        }

        return command.toArray(new String[0]);
    }

    private void processToolInfos(EnvVars envVars, Map<String, List<ToolInfo>> allToolInfos, AgentInfo agentInfo) throws Exception {
        for (Map.Entry<String, List<ToolInfo>> entry : allToolInfos.entrySet()) {
            for (ToolInfo toolInfo : entry.getValue()) {
                List<String> pathElements = toolInfo.pathElements();
                for (int i = 0; i < pathElements.size(); i++) {
                    String pathElement = pathElements.get(i);

                    envVars.put(PATH_VAR_PREFIX + StringUtils.upperCase(entry.getKey()) + "_" + i, pathElement);
                }

                envVars.putAll(toolInfo.environmentVariables());

                if (StringUtils.equals(entry.getKey(), PROJECT_ENV_MAVEN_TOOL_NAME)) {
                    handleMavenUserSettings(toolInfo, agentInfo);
                }
            }
        }
    }

    private void handleMavenUserSettings(ToolInfo toolInfo, AgentInfo agentInfo) throws Exception {
        String mavenUserSettingsPath = MapUtils.getString(toolInfo.unhandledProjectResources(), PROJECT_ENV_MAVEN_USER_SETTINGS_FILE);
        if (mavenUserSettingsPath != null && agentInfo.operatingSystem() != OperatingSystem.WINDOWS) {
            FilePath workspace = StepContextHelper.getWorkspace(getContext());

            FilePath primaryExecutable = workspace.child(toolInfo.primaryExecutable());
            FilePath primaryExecutableParent = primaryExecutable.getParent();
            if (primaryExecutableParent == null) {
                throw new IllegalStateException();
            }

            FilePath renamedPrimaryExecutable = primaryExecutableParent.child("_" + primaryExecutable.getName());
            primaryExecutable.copyToWithPermission(renamedPrimaryExecutable);

            primaryExecutable.write(generateMavenWrapperScript(mavenUserSettingsPath), StandardCharsets.UTF_8.name());
        }
    }

    private String generateMavenWrapperScript(String mavenUserSettingsPath) {
        return "#!/bin/sh\n" +
                "\"`dirname \"$0\"`/_`basename \"$0\"`\" -s \"" + mavenUserSettingsPath + "\" $*";
    }

    private BodyExecutionCallback createTempDirectoryCleanupCallback(FilePath tempDirectory) {
        return new GeneralNonBlockingStepExecution.TailCall() {
            @Override
            protected void finished(StepContext context) throws Exception {
                if (!skipCleanup) {
                    tempDirectory.deleteRecursive();
                }
            }
        };
    }

    private void invokeBodyWithEnvVarsAndCallback(EnvVars projectEnvVars, BodyExecutionCallback callback) throws Exception {
        getContext()
                .newBodyInvoker()
                .withContexts(createEnvironmentExpander(projectEnvVars))
                .withCallback(callback)
                .start();
    }

    private EnvironmentExpander createEnvironmentExpander(EnvVars projectEnvVars) throws Exception {
        return EnvironmentExpander
                .merge(getContext().get(EnvironmentExpander.class), new EnvironmentExpander() {
                    @Override
                    public void expand(@Nonnull EnvVars originalEnvVars) {
                        originalEnvVars.overrideAll(projectEnvVars);
                    }
                });
    }

}