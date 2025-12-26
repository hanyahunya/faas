package com.hanyahunya.provisioner.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.provisioner.application.port.in.ContainerUseCase;
import com.hanyahunya.provisioner.application.port.out.BlobStoragePort;
import com.hanyahunya.provisioner.application.port.out.ContainerOrchestrationPort;
import com.hanyahunya.provisioner.application.port.out.ContainerResultPort;
import com.hanyahunya.provisioner.application.system.ResourceCleanupService;
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
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerService implements ContainerUseCase {

    private final BlobStoragePort blobStoragePort;
    private final ContainerOrchestrationPort containerOrchestrationPort;
    private final ContainerResultPort containerResultPort;
    private final ObjectMapper objectMapper;
    private final ResourceCleanupService resourceCleanupService;

    @Value("${app.paths.workspace:/opt/workspace}")
    private String workspacePath;

    // [추가] 함수 ID별로 동시성 제어를 하기 위한 락 저장소
    private final ConcurrentHashMap<String, Object> downloadLocks = new ConcurrentHashMap<>();

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

        Path codeDir = Paths.get(workspacePath, "packages", functionIdStr);
        Path instanceDir = Paths.get(workspacePath, "instances", functionIdStr, instanceId);
        Path sockDir = instanceDir.resolve("sock");

        try {
            // [핵심 로직] 코드 준비 단계 (동시성 제어 필요)
            prepareFunctionCode(functionIdStr, codeDir, command.s3Key());

            // --- 실행 환경 준비 단계 (여기는 인스턴스별로 경로가 다르므로 동시성 문제 없음) ---

            // config.json 읽기 (위 단계에서 다운로드가 끝났으므로 안전)
            File configFile = codeDir.resolve("config.json").toFile();
            FunctionConfig config;
            try {
                config = objectMapper.readValue(configFile, FunctionConfig.class);
            } catch (Exception e) {
                log.error("Failed to parse config.json. Deleting package: {}", functionIdStr);
                FileSystemUtils.deleteRecursively(codeDir);
                throw e;
            }

            Files.createDirectories(sockDir);

            // 1. 컨테이너 실행
            containerOrchestrationPort.createAndStartContainer(
                    config.runtime(),
                    instanceId,
                    config.env(),
                    codeDir.toAbsolutePath().toString(),
                    sockDir.toAbsolutePath().toString()
            );

            // 2. [추가됨] 소켓 파일이 생성될 때까지 대기 (가상 스레드 효율적 방식)
            // 컨테이너 내부 앱이 부팅되어 파일을 만들 때까지 Invoker에게 알리지 않고 기다림
            Path socketFile = sockDir.resolve("function.sock");
            waitForSocketCreation(socketFile);

            // 3. 리소스 모니터링 등록
            String currentHostIp = getHostIp();
            resourceCleanupService.registerContainer(functionIdStr, instanceId, currentHostIp);

            // 4. 결과 전송
            String relativeSockPath = String.format("instances/%s/%s/sock/function.sock", functionIdStr, instanceId);
            ContainerInfo info = new ContainerInfo(currentHostIp, relativeSockPath);

            containerResultPort.sendContainerInfo(command.functionId(), info);
            log.info("Provisioning Done. Host: {}, Instance: {}, Path: {}", currentHostIp, instanceId, relativeSockPath);

        } catch (Exception e) {
            log.error("Provisioning Failed for [{}]: {}", functionIdStr, e.getMessage(), e);
            try {
                containerOrchestrationPort.removeContainer(instanceId);
                FileSystemUtils.deleteRecursively(instanceDir);
            } catch (Exception cleanupEx) { /* 무시 */ }
        }
    }

    /**
     * [추가] WatchService를 이용한 파일 생성 대기 (최대 5초)
     * 가상 스레드 환경에서 Thread.sleep보다 효율적이며, 파일 생성 즉시 반응합니다.
     */
    private void waitForSocketCreation(Path socketPath) {
        Path dir = socketPath.getParent();
        Path targetFileName = socketPath.getFileName();

        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            // 디렉토리에 '파일 생성' 이벤트 감시자 등록
            dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);

            // [Race Condition 방지] 감시자 등록 직전에 이미 파일이 생겼을 수도 있으니 확인
            if (Files.exists(socketPath)) {
                return;
            }

            // 이벤트 대기 (가상 스레드는 여기서 Block 되어도 OS 스레드를 점유하지 않음)
            // 최대 5초(5000ms) 동안 대기
            WatchKey key = watcher.poll(5, TimeUnit.SECONDS);

            if (key != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path createdFile = (Path) event.context();
                    // 생성된 파일 이름이 우리가 기다리던 'function.sock'인지 확인
                    if (createdFile.equals(targetFileName)) {
                        log.info("Socket file detected immediately: {}", socketPath);
                        return; // 성공!
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for socket file");
        } catch (Exception e) {
            log.warn("Error watching for socket file: {}", e.getMessage());
        }

        // 타임아웃 이후 최종 확인 (혹시 이벤트가 유실되었을 경우 대비)
        if (!Files.exists(socketPath)) {
            log.warn("Socket file not found within timeout (5s): {}", socketPath);
        }
    }

    /**
     * [추가] 함수 코드가 없으면 다운로드합니다.
     * 동일한 함수 ID에 대해 동시에 여러 요청이 오면 하나만 다운로드를 수행하도록 동기화합니다.
     */
    private void prepareFunctionCode(String functionId, Path codeDir, String s3Key) throws Exception {
        // 이 함수 ID 전용 락 객체를 가져옵니다. (없으면 생성)
        Object lock = downloadLocks.computeIfAbsent(functionId, k -> new Object());

        synchronized (lock) {
            File configFile = codeDir.resolve("config.json").toFile();

            // 1. 좀비 폴더 정리 (폴더는 있는데 config가 없는 경우)
            if (Files.exists(codeDir) && !configFile.exists()) {
                log.warn("Corrupted function package detected. Deleting: {}", functionId);
                FileSystemUtils.deleteRecursively(codeDir);
            }

            // 2. 다운로드 (폴더가 없을 때만)
            if (!Files.exists(codeDir)) {
                Files.createDirectories(codeDir);
                try {
                    log.info("Downloading code for function: {} (Thread Safe)", functionId);
                    blobStoragePort.downloadAndUnzip(s3Key, codeDir);
                } catch (Exception e) {
                    // 실패 시 빈 폴더 삭제
                    FileSystemUtils.deleteRecursively(codeDir);
                    throw e;
                }
            }

            // 3. 최종 확인
            if (!codeDir.resolve("config.json").toFile().exists()) {
                FileSystemUtils.deleteRecursively(codeDir);
                throw new RuntimeException("config.json missing after download.");
            }
        }
    }
}