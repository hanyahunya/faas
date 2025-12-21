package com.hanyahunya.invoker.application.port.out;

import com.hanyahunya.invoker.domain.model.ContainerInfo;

import java.util.Optional;
import java.util.UUID;

public interface ContainerPoolPort {
    Optional<ContainerInfo> popContainer(UUID functionId);

    void requestContainerCreation(UUID functionId);

    Optional<ContainerInfo> waitContainer(UUID functionId);

    void returnContainer(UUID functionId, ContainerInfo containerInfo);
}