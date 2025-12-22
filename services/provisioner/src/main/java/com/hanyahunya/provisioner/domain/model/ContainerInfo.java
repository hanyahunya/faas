package com.hanyahunya.provisioner.domain.model;

public record ContainerInfo(
        String agentIp,
        String sockPath
) {}