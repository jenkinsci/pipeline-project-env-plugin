package io.jenkins.plugins.projectenv.agent;

import lombok.Builder;

import java.io.Serializable;

@Builder
public record AgentInfo(OperatingSystem operatingSystem, String lineSeparator, Architecture architecture) implements Serializable {

}
