package com.hanyahunya.provisioner.application.port.in;

import java.util.UUID;

public interface ContainerUseCase {
    void createAndRunContainer(Command command);

    record Command(
            UUID functionId,
            String s3Key
    ) {}
}
