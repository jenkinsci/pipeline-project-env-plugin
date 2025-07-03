package io.jenkins.plugins.projectenv;

import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Result;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithTimeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class WithProjectEnvStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Before
    public void startSlave() throws Exception {
        jenkins.createSlave(Label.get("slave"));
    }

    @Test
    @WithTimeout(600)
    public void testStepExecution() throws Exception {
        String projectEnvConfigFileContent = readTestResource("project-env.toml");
        String userSettingsFileContent = readTestResource("user_settings.xml");

        WorkflowJob project = jenkins.createProject(WorkflowJob.class);
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

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, project.scheduleBuild2(0));
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
    @WithTimeout(600)
    public void testStepExecutionWithCustomConfigFileLocation() throws Exception {
        String projectEnvConfigFileContent = readTestResource("project-env-empty.toml");

        WorkflowJob project = jenkins.createProject(WorkflowJob.class);
        project.setDefinition(createOsSpecificPipelineDefinition("" +
                "node('slave') {\n" +
                "  writeFile text: '" + projectEnvConfigFileContent + "', file: 'etc/project-env.toml'\n" +
                "  withProjectEnv(cliVersion: '3.4.1', cliDebug: true, configFile: 'etc/project-env.toml') {\n" +
                "  }\n" +
                "}"));

        jenkins.assertBuildStatus(Result.SUCCESS, project.scheduleBuild2(0));
    }

    @Test
    @WithTimeout(600)
    public void testStepExecutionWithNonExistingConfigFile() throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class);
        project.setDefinition(createOsSpecificPipelineDefinition("""
                node('slave') {
                  withProjectEnv(cliVersion: '3.4.1', cliDebug: true) {
                  }
                }"""));

        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        assertThat(run.getLog()).contains("failed to install tools: FileNotFoundException");
    }

    private String readTestResource(String resource) throws IOException {
        return IOUtils.toString(getClass().getResource(resource), StandardCharsets.UTF_8);
    }

    private CpsFlowDefinition createOsSpecificPipelineDefinition(String pipelineDefinition) throws Descriptor.FormException {
        return new CpsFlowDefinition(SystemUtils.IS_OS_WINDOWS ?
                pipelineDefinition.replace("sh", "bat") :
                pipelineDefinition, true);
    }

}
