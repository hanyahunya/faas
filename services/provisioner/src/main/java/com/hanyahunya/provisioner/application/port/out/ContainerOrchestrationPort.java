package com.hanyahunya.provisioner.application.port.out;

import com.hanyahunya.provisioner.domain.model.Runtime;
import java.util.Map;

public interface ContainerOrchestrationPort {

    String createAndStartContainer(
            Runtime runtime,
            String instanceId,
            Map<String, String> env,
            String hostCodePath,
            String hostSockPath
    );

    void removeContainer(String instanceId);

    int getFunctionContainerCount();
}