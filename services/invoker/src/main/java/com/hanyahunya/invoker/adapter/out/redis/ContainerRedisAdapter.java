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

    // GC 관리를 위한 Redis Key 및 설정
    private static final String GC_CONTAINER_KEY = "prov:gc:container";
    private static final long CONTAINER_TTL_MINUTES = 15;

    @Override
    public Optional<ContainerInfo> popContainer(UUID functionId) {
        String key = IDLE_QUEUE_PREFIX + functionId;
        String jsonResult = stringRedisTemplate.opsForList().leftPop(key);

        return convertJsonToContainerInfo(jsonResult);
    }

    private record ColdStartRequest(String functionId, String s3Key) {}

    @Override
    public void requestContainerCreation(UUID functionId, String s3Key) {
        // [변경] Power of Two Choices 적용 (Load Balancing)
        // 항상 고정된 2개의 후보 슬롯을 산출하여 캐시 지역성을 유지함
        int slotA = getPrimarySlot(functionId);
        int slotB = getSecondarySlot(functionId);

        String queueA = REQUEST_QUEUE_PREFIX + slotA;
        String queueB = REQUEST_QUEUE_PREFIX + slotB;

        // Redis에서 두 큐의 대기열 길이를 조회 (네트워크 왕복 2회 발생하지만 LLEN은 매우 빠름)
        // *최적화 팁: Lua Script나 Pipelining을 쓰면 1회로 줄일 수 있음
        Long lenA = stringRedisTemplate.opsForList().size(queueA);
        Long lenB = stringRedisTemplate.opsForList().size(queueB);

        if (lenA == null) lenA = 0L;
        if (lenB == null) lenB = 0L;

        // 더 짧은 대기열 선택 (부하 분산)
        String targetQueue = (lenA <= lenB) ? queueA : queueB;

        ColdStartRequest request = new ColdStartRequest(functionId.toString(), s3Key);

        try {
            String jsonPayload = objectMapper.writeValueAsString(request);
            stringRedisTemplate.opsForList().rightPush(targetQueue, jsonPayload);
            log.info("Requested creation: Queue[{}] (len: A={}, B={}) -> {}", targetQueue, lenA, lenB, functionId);
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

            String instanceId = extractInstanceId(containerInfo.sockPath());
            if (instanceId != null) {
                updateContainerHeartbeat(functionId.toString(), instanceId, containerInfo.agentIp());
            }

            log.info("Container returned to pool: [{}] -> {}", functionId, containerInfo.agentIp());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ContainerInfo", e);
        } catch (Exception e) {
            log.error("Failed to return container to Redis: {}", e.getMessage(), e);
        }
    }

    private String extractInstanceId(String sockPath) {
        try {
            String[] parts = sockPath.split("/");
            if (parts.length > 2) {
                return parts[2];
            }
        } catch (Exception e) {
            log.warn("Failed to extract instanceId from path: {}", sockPath);
        }
        return null;
    }

    private void updateContainerHeartbeat(String functionId, String instanceId, String agentIp) {
        String member = functionId + ":" + instanceId + ":" + agentIp;
        long expiryTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CONTAINER_TTL_MINUTES);
        stringRedisTemplate.opsForZSet().add(GC_CONTAINER_KEY, member, expiryTime);
    }

    // [변경] 첫 번째 후보 슬롯 (기존 방식)
    private int getPrimarySlot(UUID functionId) {
        return Math.abs(functionId.hashCode()) % TOTAL_PARTITIONS;
    }

    // [추가] 두 번째 후보 슬롯 (Salt 사용으로 고정된 다른 값 산출)
    private int getSecondarySlot(UUID functionId) {
        // "secondary"라는 문자열을 붙여서 해시를 다르게 만듦
        // 같은 functionId라면 언제나 같은 slotB가 나옴
        return Math.abs((functionId.toString() + ":secondary").hashCode()) % TOTAL_PARTITIONS;
    }

    private Optional<ContainerInfo> convertJsonToContainerInfo(String jsonResult) {
        if (jsonResult == null) {
            return Optional.empty();
        }
        try {
            ContainerInfo info = objectMapper.readValue(jsonResult, ContainerInfo.class);
            return Optional.of(info);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Redis JSON result: {}", jsonResult, e);
            return Optional.empty();
        }
    }
}