package io.jenkins.plugins.projectenv.proc;

import lombok.Builder;

@Builder
public record ProcResult(
        int exitCode,
        String stdOutput
) {
}
