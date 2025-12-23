package com.hanyahunya.provisioner.adapter.out.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.hanyahunya.provisioner.application.port.out.ContainerOrchestrationPort;
import com.hanyahunya.provisioner.domain.model.Runtime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerAdapter implements ContainerOrchestrationPort {

    private final DockerClient dockerClient;

    private static final String CONTAINER_CODE_PATH = "/var/task";
    private static final String CONTAINER_SOCK_DIR = "/var/run";

    @Override
    public String createAndStartContainer(Runtime runtime, String instanceId, Map<String, String> env, String hostCodePath, String hostSockPath) {
        String imageTag = mapRuntimeToImage(runtime);
        String containerName = "ins-" + instanceId;

        log.info("Docker: Provisioning Start [Image: {}, Name: {}]", imageTag, containerName);

        removeContainerIfExists(containerName);

        // 컨테이너 생성 및 시작
        String containerId = createAndStartDockerContainer(imageTag, containerName, env, hostCodePath, hostSockPath);

        // 소켓 파일 생성 대기 (Health Check)
        // 호스트 경로의 sock 폴더를 감시
        File sockFile = Paths.get(hostSockPath, "function.sock").toFile();
        boolean isReady = waitForSocketFile(sockFile);

        if (!isReady) {
            log.error("Container failed to initialize socket. Removing container.");
            removeContainerIfExists(containerName);
            throw new RuntimeException("Container initialization failed: socket file not created.");
        }

        // 즉시 얼리기 (Pause)
        pauseContainer(instanceId);

        log.info("Docker: Provisioning Completed (Paused) [{}]", containerId);
        return containerId;
    }

    // 컨테이너 일시정지
    private void pauseContainer(String instanceId) {
        String containerName = "ins-" + instanceId;
        try {
            dockerClient.pauseContainerCmd(containerName).exec();
            log.debug("Container Paused: {}", containerName);
        } catch (Exception e) {
            log.warn("Failed to pause container [{}]: {}", containerName, e.getMessage());
        }
    }

    // 내부 메서드: 순수 Docker 생성/시작 로직
    private String createAndStartDockerContainer(String imageTag, String containerName, Map<String, String> env, String hostCodePath, String hostSockPath) {
        List<String> envList = new ArrayList<>();
        if (env != null) {
            env.forEach((k, v) -> envList.add(k + "=" + v));
        }
        envList.add("SOCK_PATH=" + CONTAINER_SOCK_DIR + "/function.sock");

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(
                        Bind.parse(hostCodePath + ":" + CONTAINER_CODE_PATH + ":ro"),
                        Bind.parse(hostSockPath + ":" + CONTAINER_SOCK_DIR + ":rw")
                )
                .withMemory(256 * 1024 * 1024L)
                .withCpuPeriod(100000L)
                .withCpuQuota(50000L);

        try {
            CreateContainerResponse container = dockerClient.createContainerCmd(imageTag)
                    .withName(containerName)
                    .withEnv(envList)
                    .withHostConfig(hostConfig)
                    .exec();

            String containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();
            return containerId;

        } catch (Exception e) {
            throw new RuntimeException("Failed to start docker container", e);
        }
    }

    private boolean waitForSocketFile(File file) {
        Path dir = file.getParentFile().toPath();
        Path targetFileName = file.toPath().getFileName();

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // 디렉토리의 파일생성 이벤트 등록
            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            if (file.exists()) {
                return true;
            }

            // 최대 대기 시단
            long remainingNanos = TimeUnit.SECONDS.toNanos(5);
            long endNanos = System.nanoTime() + remainingNanos;

            while (remainingNanos > 0) {
                // 이벤트가 올때까지 블로킹 (가상 스레드)
                WatchKey key = watchService.poll(remainingNanos, TimeUnit.NANOSECONDS);

                if (key == null) return false; // 타임아웃

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context().equals(targetFileName)) {
                        return true;
                    }
                }

                // 이벤트 키 초기화
                boolean valid = key.reset();
                if (!valid) break;

                // 남은 시간 계산해서 다시 루프
                remainingNanos = endNanos - System.nanoTime();
            }
        } catch (IOException | InterruptedException e) {
            log.warn("File Watch Error [{}]: {}", file.getName(), e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }

        return file.exists();
    }

    private void removeContainerIfExists(String containerName) {
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception e) { /* 무시 */ }
    }

    private String mapRuntimeToImage(Runtime runtime) {
        return switch (runtime) {
            case JAVA_17 -> "runtime-java:17";
            case JAVA_21 -> "runtime-java:21";
            case PYTHON_3_10 -> "runtime-python:3.10";
            case PYTHON_3_11 -> "runtime-python:3.11";
            case NODE_18 -> "runtime-node:18";
            case NODE_20 -> "runtime-node:20";
        };
    }
}