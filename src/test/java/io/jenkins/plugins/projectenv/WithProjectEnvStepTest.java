package io.jenkins.plugins.projectenv;

import hudson.model.Label;
import hudson.model.Result;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@WithJenkins
class WithProjectEnvStepTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.createSlave(Label.get("slave"));
    }

    @Test
    @Timeout(600)
    void testStepExecution() throws Exception {
        String projectEnvConfigFileContent = readTestResource("project-env.toml");
        String userSettingsFileContent = readTestResource("user_settings.xml");

        WorkflowJob project = j.createProject(WorkflowJob.class);
        project.setDefinition(createOsSpecificPipelineDefinition("" +
                "node('slave') {\n" +
                "  writeFile text: '''" + projectEnvConfigFileContent + "''', file: 'project-env.toml'\n" +
                "  writeFile text: '''" + userSettingsFileContent + "''', file: 'user_settings.xml'\n" +
                "  println \"PATH: ${env.PATH}\"\n" +
                "  withProjectEnv(cliDebug: true) {\n" +
                "    println \"PATH: ${env.PATH}\"\n" +
                "    sh 'java -version'\n" +
                "    sh 'native-image --version'\n" +
                "    sh 'mvn --version'\n" +
                "    sh 'mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout'\n" +
                "    sh 'gradle --version'\n" +
                "    sh 'node --version'\n" +
                "    sh 'yarn --version'\n" +
                "    sh 'which project-env-cli'\n" +
                "  }\n" +
                "}"));

        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, project.scheduleBuild2(0));
        assertThat(run.getLog())
                // assert that the JDK (including native-image) has been installed
                .contains("installing jdk...")
                .contains("openjdk version \"17.0.9\" 2023-10-17")
                .contains("native-image 17.0.9 2023-10-17")
                // assert that Maven has been installed
                .contains("installing maven...")
                .contains("Apache Maven 3.8.4 (9b656c72d54e5bacbed989b64718c159fe39b537)")
                .contains("/tmp/m2repo")
                // assert that Gradle has been installed
                .contains("installing gradle...")
                .contains("Gradle 7.3")
                // assert that NodeJS (including yarn) has been installed
                .contains("installing nodejs...")
                .contains("v17.2.0")
                .contains("1.22.18")
                // assert that Project-Env CLI is on the PATH
                .containsPattern("workspace/test\\d+@tmp/withProjectEnv[^/]+/project-env-cli");
    }

    @Test
    @Timeout(600)
    void testStepExecutionWithCustomConfigFileLocation() throws Exception {
        String projectEnvConfigFileContent = readTestResource("project-env-empty.toml");

        WorkflowJob project = j.createProject(WorkflowJob.class);
        project.setDefinition(createOsSpecificPipelineDefinition("" +
                "node('slave') {\n" +
                "  writeFile text: '" + projectEnvConfigFileContent + "', file: 'etc/project-env.toml'\n" +
                "  withProjectEnv(cliVersion: '3.4.1', cliDebug: true, configFile: 'etc/project-env.toml') {\n" +
                "  }\n" +
                "}"));

        j.assertBuildStatus(Result.SUCCESS, project.scheduleBuild2(0));
    }

    @Test
    @Timeout(600)
    void testStepExecutionWithNonExistingConfigFile() throws Exception {
        WorkflowJob project = j.createProject(WorkflowJob.class);
        project.setDefinition(createOsSpecificPipelineDefinition("" +
                "node('slave') {\n" +
                "  withProjectEnv(cliVersion: '3.4.1', cliDebug: true) {\n" +
                "  }\n" +
                "}"));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        assertThat(run.getLog()).contains("failed to install tools: FileNotFoundException");
    }

    private String readTestResource(String resource) throws IOException {
        return IOUtils.toString(getClass().getResource(resource), StandardCharsets.UTF_8);
    }

    private CpsFlowDefinition createOsSpecificPipelineDefinition(String pipelineDefinition) {
        return new CpsFlowDefinition(SystemUtils.IS_OS_WINDOWS ?
                pipelineDefinition.replace("sh", "bat") :
                pipelineDefinition, true);
    }

}
