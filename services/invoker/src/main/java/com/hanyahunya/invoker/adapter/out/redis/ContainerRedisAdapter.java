package com.hanyahunya.invoker.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.invoker.application.port.out.ContainerPoolPort;
import com.hanyahunya.invoker.domain.model.ContainerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerRedisAdapter implements ContainerPoolPort {

    private final RedisTemplate<String, Object> jsonRedisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String IDLE_QUEUE_PREFIX = "func:idle:";
    private static final String REQUEST_QUEUE_PREFIX = "func:request:queue";
    private static final int TOTAL_PARTITIONS = 16;

    @Override
    public Optional<ContainerInfo> popContainer(UUID functionId) {
        String key = IDLE_QUEUE_PREFIX + functionId;
        Object result = jsonRedisTemplate.opsForList().leftPop(key);

        return convertResultToContainerInfo(result);
    }

    private record ColdStartRequest(String functionId, String s3Key) {}

    @Override
    public void requestContainerCreation(UUID functionId, String s3Key) {
        int slot = getSlotIndex(functionId);
        String targetQueue = REQUEST_QUEUE_PREFIX + slot;

        try {
            // JSON 문자열로 변환
            ColdStartRequest request = new ColdStartRequest(functionId.toString(), s3Key);
            String messageJson = objectMapper.writeValueAsString(request);

            // RPUSH
            stringRedisTemplate.opsForList().rightPush(targetQueue, messageJson);

            log.info("Requested creation: Queue[{}] -> {}", targetQueue, messageJson);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ColdStartRequest", e);
            throw new RuntimeException("Redis Message Serialization Failed");
        }
    }

    @Override
    public Optional<ContainerInfo> waitContainer(UUID functionId) {
        String key = IDLE_QUEUE_PREFIX + functionId;

        Object result = jsonRedisTemplate.opsForList().leftPop(key, 3, TimeUnit.MINUTES);

        return convertResultToContainerInfo(result);
    }

    private int getSlotIndex(UUID functionId) {
        return Math.abs(functionId.hashCode()) % TOTAL_PARTITIONS;
    }

    @Override
    public void returnContainer(UUID functionId, ContainerInfo containerInfo) {
        String key = IDLE_QUEUE_PREFIX + functionId;
        try {
            jsonRedisTemplate.opsForList().rightPush(key, containerInfo);
            log.info("Container returned to pool: [{}] -> {}", functionId, containerInfo.agentIp());
        } catch (Exception e) {
            log.error("Failed to return container to Redis: {}", e.getMessage(), e);
        }
    }

    private Optional<ContainerInfo> convertResultToContainerInfo(Object result) {
        if (result == null) {
            return Optional.empty();
        }
        try {
            ContainerInfo info = objectMapper.convertValue(result, ContainerInfo.class);
            return Optional.of(info);
        } catch (IllegalArgumentException e) {
            log.error("Failed to convert Redis result to ContainerInfo", e);
            return Optional.empty();
        }
    }
}