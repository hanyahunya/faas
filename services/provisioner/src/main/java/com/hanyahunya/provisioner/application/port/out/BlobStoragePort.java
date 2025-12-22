package com.hanyahunya.provisioner.application.port.out;

import java.nio.file.Path;

public interface BlobStoragePort {

    void downloadAndUnzip(String key, Path destinationDir);
}