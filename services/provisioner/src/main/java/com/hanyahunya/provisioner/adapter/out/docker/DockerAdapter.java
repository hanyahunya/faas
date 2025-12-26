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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

        log.info("Docker: Creating Container [Image: {}, Name: {}]", imageTag, containerName);

        removeContainerIfExists(containerName);

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
                // [수정] 메모리 제한: 256MB -> 100MB (안전한 최소치)
                .withMemory(100 * 1024 * 1024L)
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
            throw new RuntimeException("Failed to start container", e);
        }
    }

    @Override
    public void removeContainer(String instanceId) {
        String containerName = "ins-" + instanceId;
        removeContainerIfExists(containerName);
        log.info("Docker Container Removed: {}", containerName);
    }

    // [추가] 현재 떠있는 함수 컨테이너(ins-*) 개수 카운팅
    @Override
    public int getFunctionContainerCount() {
        try {
            // "ins-"로 시작하는 컨테이너만 필터링하여 개수 반환
            // status=running 조건도 추가 가능하나, 일단 존재하는 모든 함수 컨테이너를 리소스로 간주
            return dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(Collections.singleton("ins-"))
                    .exec()
                    .size();
        } catch (Exception e) {
            log.warn("Failed to count containers", e);
            return 0;
        }
    }

    private void removeContainerIfExists(String containerName) {
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception e) {
            // 이미 없으면 무시
        }
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