package com.hanyahunya.provisioner.adapter.out.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.provisioner.application.port.out.ContainerResultPort;
import com.hanyahunya.provisioner.domain.model.ContainerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisContainerResultAdapter implements ContainerResultPort {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String IDLE_QUEUE_PREFIX = "func:idle:";

    @Override
    public void sendContainerInfo(UUID functionId, ContainerInfo info) {
        String key = IDLE_QUEUE_PREFIX + functionId;
        try {
            String jsonValue = objectMapper.writeValueAsString(info);

            stringRedisTemplate.opsForList().rightPush(key, jsonValue);

            log.info("Container Info sent to Redis [{}]: {}", key, jsonValue);
        } catch (Exception e) {
            log.error("Failed to send container info to Redis", e);
            throw new RuntimeException("Redis Push Failed", e);
        }
    }
}