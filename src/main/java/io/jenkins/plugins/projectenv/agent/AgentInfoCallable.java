package io.jenkins.plugins.projectenv.agent;

import jenkins.agents.ControllerToAgentCallable;
import org.apache.commons.lang3.SystemUtils;

public class AgentInfoCallable implements ControllerToAgentCallable<AgentInfo, Exception> {

    @Override
    public AgentInfo call() {
        return AgentInfo.builder()
                .lineSeparator(System.lineSeparator())
                .operatingSystem(getOperatingSystem())
                .architecture(getArchitecture())
                .build();
    }

    private OperatingSystem getOperatingSystem() {
        if (SystemUtils.IS_OS_WINDOWS) {
            return OperatingSystem.WINDOWS;
        } else if (SystemUtils.IS_OS_MAC) {
            return OperatingSystem.MACOS;
        } else if (SystemUtils.IS_OS_LINUX) {
            return OperatingSystem.LINUX;
        } else {
            throw new IllegalStateException("unsupported OS " + SystemUtils.OS_NAME);
        }
    }

    private Architecture getArchitecture() {
        if (SystemUtils.OS_ARCH.equalsIgnoreCase("aarch64")) {
            return Architecture.AARCH64;
        } else {
            return Architecture.AMD64;
        }
    }

}
