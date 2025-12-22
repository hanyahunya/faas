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

        // 함수 ID가 아니라, 이번 컨테이너의 고유 ID(UUID)를 이름으로 사용
        // ins-550e8400-e29b-41d4-a716-446655440000
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
                        // Code 공유 폴더 (Read-Only)
                        Bind.parse(hostCodePath + ":" + CONTAINER_CODE_PATH + ":ro"),
                        // Sock 격리 폴더 (Read-Write)
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
            throw new RuntimeException("Failed to start container", e);
        }
    }

    private void removeContainerIfExists(String containerName) {
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception e) {
            // 무시
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