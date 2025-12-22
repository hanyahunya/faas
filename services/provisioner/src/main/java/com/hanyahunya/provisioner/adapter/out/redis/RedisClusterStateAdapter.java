package com.hanyahunya.provisioner.adapter.out.redis;

import com.hanyahunya.provisioner.application.port.out.ClusterStatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RedisClusterStateAdapter implements ClusterStatePort {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> jsonRedisTemplate;

    private static final String HEARTBEAT_PREFIX = "prov:node:";
    private static final String LEADER_KEY = "prov:leader";
    private static final String ASSIGN_KEY_PREFIX = "prov:assign:";

    // 노드 TTL 5초, 리더 TTL 10초
    private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(5);
    private static final Duration LEADER_TTL = Duration.ofSeconds(10);

    @Override
    public void sendHeartbeat(String nodeId) {
        stringRedisTemplate.opsForValue().set(HEARTBEAT_PREFIX + nodeId, "alive", HEARTBEAT_TTL);
    }

    @Override
    public Set<String> getActiveNodes() {
        Set<String> keys = stringRedisTemplate.keys(HEARTBEAT_PREFIX + "*");
        if (keys == null) return Collections.emptySet();
        return keys.stream()
                .map(k -> k.replace(HEARTBEAT_PREFIX, ""))
                .collect(Collectors.toSet());
    }

    @Override
    public boolean tryAcquireLeadership(String nodeId) {
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(LEADER_KEY, nodeId, LEADER_TTL);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void extendLeadership(String nodeId) {
        String currentLeader = stringRedisTemplate.opsForValue().get(LEADER_KEY);
        if (nodeId.equals(currentLeader)) {
            stringRedisTemplate.expire(LEADER_KEY, LEADER_TTL);
        }
    }

    @Override
    public boolean isLeader(String nodeId) {
        String currentLeader = stringRedisTemplate.opsForValue().get(LEADER_KEY);
        return nodeId.equals(currentLeader);
    }

    @Override
    public void saveAssignmentRange(String nodeId, int start, int end) {
        Range range = new Range(start, end);
        jsonRedisTemplate.opsForValue().set(ASSIGN_KEY_PREFIX + nodeId, range);
    }

    @Override
    public Range getAssignmentRange(String nodeId) {
        Object result = jsonRedisTemplate.opsForValue().get(ASSIGN_KEY_PREFIX + nodeId);
        if (result != null) {
            return (Range) result;
        }
        return null;
    }
}