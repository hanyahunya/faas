package com.hanyahunya.provisioner.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.provisioner.application.port.in.ContainerUseCase;
import com.hanyahunya.provisioner.application.port.out.BlobStoragePort;
import com.hanyahunya.provisioner.application.port.out.ContainerOrchestrationPort;
import com.hanyahunya.provisioner.application.port.out.ContainerResultPort;
import com.hanyahunya.provisioner.domain.model.ContainerInfo;
import com.hanyahunya.provisioner.domain.model.FunctionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerService implements ContainerUseCase {

    private final BlobStoragePort blobStoragePort;
    private final ContainerOrchestrationPort containerOrchestrationPort;
    private final ContainerResultPort containerResultPort;
    private final ObjectMapper objectMapper;

    // [경로 통합] Provisioner의 파일 I/O 경로이자 Docker 마운트 경로
    @Value("${app.paths.workspace:/opt/workspace}")
    private String workspacePath;

    private String getHostIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("Failed to resolve Host IP. Fallback to 127.0.0.1", e);
            return "127.0.0.1";
        }
    }

    @Override
    public void createAndRunContainer(Command command) {
        String functionIdStr = command.functionId().toString();
        String instanceId = UUID.randomUUID().toString();

        // [Code 영역] ~/packages/{functionId}
        Path codeDir = Paths.get(workspacePath, "packages", functionIdStr);

        // [Run 영역] ~/instances/{functionId}/{instanceId}
        Path instanceDir = Paths.get(workspacePath, "instances", functionIdStr, instanceId);
        Path sockDir = instanceDir.resolve("sock");

        try {
            // --- 코드 준비 단계 ---
            if (!Files.exists(codeDir)) {
                Files.createDirectories(codeDir);
                blobStoragePort.downloadAndUnzip(command.s3Key(), codeDir);
                log.info("Code downloaded for function: {}", functionIdStr);
            }

            File configFile = codeDir.resolve("config.json").toFile();
            if (!configFile.exists()) {
                FileSystemUtils.deleteRecursively(codeDir);
                throw new RuntimeException("config.json missing. Cleaned up code dir.");
            }
            FunctionConfig config = objectMapper.readValue(configFile, FunctionConfig.class);

            // --- 실행 환경 준비 단계 ---
            Files.createDirectories(sockDir);

            // 컨테이너 실행
            containerOrchestrationPort.createAndStartContainer(
                    config.runtime(),
                    instanceId,
                    config.env(),
                    codeDir.toAbsolutePath().toString(),
                    sockDir.toAbsolutePath().toString()
            );

            // 4결과 전송
            // 이런식 -> instances/{functionId}/{instanceId}/sock/function.sock
            String relativeSockPath = String.format("instances/%s/%s/sock/function.sock", functionIdStr, instanceId);

            String currentHostIp = getHostIp();

            ContainerInfo info = new ContainerInfo(currentHostIp, relativeSockPath);

            containerResultPort.sendContainerInfo(command.functionId(), info);
            log.info("Provisioning Done. Host: {}, Instance: {}, Path: {}", currentHostIp, instanceId, relativeSockPath);

        } catch (Exception e) {
            log.error("Provisioning Failed for [{}]: {}", functionIdStr, e.getMessage(), e);
            try {
                FileSystemUtils.deleteRecursively(instanceDir);
            } catch (Exception cleanupEx) { /* 무시 */ }
        }
    }
}