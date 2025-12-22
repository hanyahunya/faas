package com.hanyahunya.invoker.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.invoker.application.port.out.ContainerPoolPort;
import com.hanyahunya.invoker.domain.model.ContainerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerRedisAdapter implements ContainerPoolPort {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String IDLE_QUEUE_PREFIX = "func:idle:";
    private static final String REQUEST_QUEUE_PREFIX = "func:request:queue:";
    private static final int TOTAL_PARTITIONS = 16384;

    @Override
    public Optional<ContainerInfo> popContainer(UUID functionId) {
        String key = IDLE_QUEUE_PREFIX + functionId;
        String jsonResult = stringRedisTemplate.opsForList().leftPop(key);

        return convertJsonToContainerInfo(jsonResult);
    }

    private record ColdStartRequest(String functionId, String s3Key) {}

    @Override
    public void requestContainerCreation(UUID functionId, String s3Key) {
        int slot = getSlotIndex(functionId);
        String targetQueue = REQUEST_QUEUE_PREFIX + slot;

        ColdStartRequest request = new ColdStartRequest(functionId.toString(), s3Key);

        try {
            String jsonPayload = objectMapper.writeValueAsString(request);
            stringRedisTemplate.opsForList().rightPush(targetQueue, jsonPayload);
            log.info("Requested creation: Queue[{}] -> {}", targetQueue, jsonPayload);
        } catch (Exception e) {
            log.error("Failed to push ColdStartRequest to Redis", e);
            throw new RuntimeException("Redis Push Failed");
        }
    }

    @Override
    public Optional<ContainerInfo> waitContainer(UUID functionId) {
        String key = IDLE_QUEUE_PREFIX + functionId;

        String jsonResult = stringRedisTemplate.opsForList().leftPop(key, 3, TimeUnit.MINUTES);

        return convertJsonToContainerInfo(jsonResult);
    }

    @Override
    public void returnContainer(UUID functionId, ContainerInfo containerInfo) {
        String key = IDLE_QUEUE_PREFIX + functionId;
        try {
            String jsonValue = objectMapper.writeValueAsString(containerInfo);
            stringRedisTemplate.opsForList().rightPush(key, jsonValue);

            log.info("Container returned to pool: [{}] -> {}", functionId, containerInfo.agentIp());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ContainerInfo", e);
        } catch (Exception e) {
            log.error("Failed to return container to Redis: {}", e.getMessage(), e);
        }
    }

    private int getSlotIndex(UUID functionId) {
        return Math.abs(functionId.hashCode()) % TOTAL_PARTITIONS;
    }

    // Object가 아닌 String을 받아서 처리
    private Optional<ContainerInfo> convertJsonToContainerInfo(String jsonResult) {
        if (jsonResult == null) {
            return Optional.empty();
        }
        try {
            // 순수 JSON 문자열 -> Java 객체 변환
            ContainerInfo info = objectMapper.readValue(jsonResult, ContainerInfo.class);
            return Optional.of(info);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Redis JSON result: {}", jsonResult, e);
            return Optional.empty();
        }
    }
}