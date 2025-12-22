package com.hanyahunya.provisioner.adapter.out.aws;

import com.hanyahunya.provisioner.application.port.out.BlobStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3Adapter implements BlobStoragePort {

    private final S3Client s3Client;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    @Override
    public void downloadAndUnzip(String key, Path destinationDir) {
        log.info("S3 Download & Unzip Start: s3://{}/{} -> {}", bucketName, key, destinationDir);

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            // ZIP 파일을 디스크에 저장하지 않고, 스트림에서 바로 읽어 압축해제
            try (ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(request)) {
                unzip(s3Stream, destinationDir.toFile());
            }

            log.info("S3 Download & Unzip Success");

        } catch (Exception e) {
            log.error("S3 Operation Failed: {}", e.getMessage());
            throw new RuntimeException("S3 Download & Unzip Failed", e);
        }
    }

    // 압축 해제 로직
    private void unzip(InputStream inputStream, File destDir) throws IOException {
        byte[] buffer = new byte[1024];
        // S3 InputStream을 바로 ZipInputStream으로 감싸서 처리
        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create dir " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create dir " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
        }
    }

    // Zip Slip 취약점 방지 로직 포함
    private File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());
        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }
        return destFile;
    }
}