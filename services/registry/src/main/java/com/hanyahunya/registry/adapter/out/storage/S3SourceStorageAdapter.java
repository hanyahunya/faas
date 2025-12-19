package com.hanyahunya.registry.adapter.out.storage;

import com.hanyahunya.registry.application.port.out.SourceStoragePort;
import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
@RequiredArgsConstructor
public class S3SourceStorageAdapter implements SourceStoragePort {
    private final S3Template s3Template;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    @Override
    public void upload(byte[] content, String s3Key) {
        try {
            s3Template.upload(bucketName, s3Key, new ByteArrayInputStream(content));
        } catch (Exception e) {
            throw new RuntimeException("S3 Upload Failed", e);
        }
    }

    @Override
    public void delete(String s3Key) {
        if(s3Key != null) s3Template.deleteObject(bucketName, s3Key);
    }
}