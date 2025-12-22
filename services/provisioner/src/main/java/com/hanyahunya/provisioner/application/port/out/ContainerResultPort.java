package com.hanyahunya.provisioner.application.port.out;

import com.hanyahunya.provisioner.domain.model.ContainerInfo;
import java.util.UUID;

public interface ContainerResultPort {
    void sendContainerInfo(UUID functionId, ContainerInfo info);
}