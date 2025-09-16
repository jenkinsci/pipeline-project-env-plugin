package io.jenkins.plugins.projectenv.toolinfo;


import java.util.List;
import java.util.Map;

public record ToolInfo(
        String primaryExecutable,
        Map<String, String> environmentVariables,
        List<String> pathElements,
        Map<String, String> unhandledProjectResources
) {

}
