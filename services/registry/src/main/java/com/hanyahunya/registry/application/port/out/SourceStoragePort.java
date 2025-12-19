package com.hanyahunya.registry.application.port.out;

public interface SourceStoragePort {
    void upload(byte[] content, String s3Key);
    void delete(String s3Key);
}