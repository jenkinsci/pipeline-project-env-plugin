package io.jenkins.plugins.projectenv.agent;

import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;

public class AgentInfoCallable extends MasterToSlaveCallable<AgentInfo, Exception> {

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
        if (StringUtils.equalsIgnoreCase(SystemUtils.OS_ARCH, "aarch64")) {
            return Architecture.AARCH64;
        } else {
            return Architecture.AMD64;
        }
    }

}
