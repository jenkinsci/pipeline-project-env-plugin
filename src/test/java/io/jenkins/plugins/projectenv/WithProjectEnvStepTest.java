package io.jenkins.plugins.projectenv;

import hudson.model.Descriptor;
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
        String pipeline = """
                node('slave') {
                  writeFile text: '''%s''', file: 'project-env.toml'
                  writeFile text: '''%s''', file: 'user_settings.xml'
                  println "PATH: ${env.PATH}"
                  withProjectEnv(cliDebug: true) {
                    println "PATH: ${env.PATH}"
                    sh 'java -version'
                    sh 'native-image --version'
                    sh 'mvn --version'
                    sh 'mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout'
                    sh 'gradle --version'
                    sh 'node --version'
                    sh 'yarn --version'
                    sh 'which project-env-cli'
                  }
                }
                """.formatted(projectEnvConfigFileContent, userSettingsFileContent);
        project.setDefinition(createOsSpecificPipelineDefinition(pipeline));

        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, project.scheduleBuild2(0));
        assertThat(run.getLog())
                // assert that the JDK (including native-image) has been installed
                .contains("installing jdk...")
                .contains("openjdk version \"21.0.2\" 2024-01-16")
                .contains("native-image 21.0.2 2024-01-16")
                // assert that Maven has been installed
                .contains("installing maven...")
                .contains("Apache Maven 3.8.4 (9b656c72d54e5bacbed989b64718c159fe39b537)")
                .contains("/tmp/m2repo")
                // assert that Gradle has been installed
                .contains("installing gradle...")
                .contains("Gradle 9.0.0")
                // assert that NodeJS (including yarn) has been installed
                .contains("installing nodejs...")
                .contains("v22.19.0")
                .contains("1.22.22")
                // assert that Project-Env CLI is on the PATH
                .containsPattern("workspace/test\\d+@tmp/withProjectEnv[^/]+/project-env-cli");
    }

    @Test
    @Timeout(600)
    void testStepExecutionWithCustomConfigFileLocation() throws Exception {
        String projectEnvConfigFileContent = readTestResource("project-env-empty.toml");

        WorkflowJob project = j.createProject(WorkflowJob.class);
        String pipeline = """
                node('slave') {
                  writeFile text: '%s', file: 'etc/project-env.toml'
                  withProjectEnv(cliDebug: true, configFile: 'etc/project-env.toml') {
                  }
                }
                """.formatted(projectEnvConfigFileContent);
        project.setDefinition(createOsSpecificPipelineDefinition(pipeline));

        j.assertBuildStatus(Result.SUCCESS, project.scheduleBuild2(0));
    }

    @Test
    @Timeout(600)
    void testStepExecutionWithNonExistingConfigFile() throws Exception {
        WorkflowJob project = j.createProject(WorkflowJob.class);
        String pipeline = """
                node('slave') {
                  withProjectEnv(cliDebug: true) {
                  }
                }
                """;
        project.setDefinition(createOsSpecificPipelineDefinition(pipeline));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        assertThat(run.getLog()).contains("failed to install tools: FileNotFoundException");
    }

    private String readTestResource(String resource) throws IOException {
        return IOUtils.toString(getClass().getResource(resource), StandardCharsets.UTF_8);
    }

    private CpsFlowDefinition createOsSpecificPipelineDefinition(String pipelineDefinition) {
        try {
            return new CpsFlowDefinition(SystemUtils.IS_OS_WINDOWS ?
                    pipelineDefinition.replace("sh", "bat") :
                    pipelineDefinition, true);
        } catch (Descriptor.FormException e) {
            throw new RuntimeException(e);
        }
    }

}
