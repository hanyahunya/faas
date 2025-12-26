package com.hanyahunya.provisioner.application.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.provisioner.application.port.out.ContainerOrchestrationPort;
import com.hanyahunya.provisioner.domain.model.ContainerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceCleanupService {

    private final ContainerOrchestrationPort containerPort;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.paths.workspace:/opt/workspace}")
    private String workspacePath;

    private static final String GC_CONTAINER_KEY = "prov:gc:container";
    private static final String IDLE_QUEUE_PREFIX = "func:idle:";

    // 내 서버 IP 확인용
    private String getMyHostIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * [수정됨] 컨테이너 등록 시 IP 정보도 함께 저장합니다.
     * 저장 포맷: "functionId:instanceId:agentIp"
     */
    public void registerContainer(String functionId, String instanceId, String agentIp) {
        // 구분자로 합쳐서 저장 (IP가 있어야 나중에 JSON을 재조립해서 지울 수 있음)
        String member = functionId + ":" + instanceId + ":" + agentIp;

        long expiryTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15);
        stringRedisTemplate.opsForZSet().add(GC_CONTAINER_KEY, member, expiryTime);

        log.info("Registered for Cleanup: {} (Expires in 15min)", member);
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredContainers() {
        long now = System.currentTimeMillis();

        // 1. 만료된 멤버 조회
        Set<String> expiredMembers = stringRedisTemplate.opsForZSet().rangeByScore(GC_CONTAINER_KEY, 0, now);
        if (expiredMembers == null || expiredMembers.isEmpty()) return;

        String myIp = getMyHostIp();
        // 3분(180000ms)의 유예 기간 설정
        long gracePeriod = TimeUnit.MINUTES.toMillis(3);

        for (String member : expiredMembers) {
            try {
                // ... (파싱 로직 동일) ...
                String[] parts = member.split(":");
                // ... 파싱 예외처리 ...
                String functionId = parts[0];
                String instanceId = parts[1];
                String targetAgentIp = parts[2];

                // 2. Redis Idle List 청소 (글로벌: 누구나 수행)
                // 이미 삭제되었어도 멱등성(Idempotency)이 있으므로 실행해도 안전
                removeFromIdleQueue(functionId, instanceId, targetAgentIp);

                // 3. 만료된 지 얼마나 지났는지 확인 (Score = 만료 예정 시간)
                Double score = stringRedisTemplate.opsForZSet().score(GC_CONTAINER_KEY, member);
                long expiryTime = (score != null) ? score.longValue() : 0;
                long timePassedSinceExpiry = now - expiryTime;

                boolean isOwner = targetAgentIp.equals(myIp);
                boolean isDeadOwner = timePassedSinceExpiry > gracePeriod; // 만료된지 3분 넘음?

                // 4. 리소스 정리 및 ZSet 삭제 판단
                if (isOwner) {
                    // [Case A] 내가 주인인 경우: 파일 지우고 명부에서 삭제
                    deleteContainerResources(functionId, instanceId);
                    stringRedisTemplate.opsForZSet().remove(GC_CONTAINER_KEY, member);

                } else if (isDeadOwner) {
                    // [Case B] 남의 것인데 3분 넘게 방치됨: 주인이 죽었다고 판단 -> 명부 강제 삭제
                    log.warn("Force removing orphaned GC entry (Owner dead?): {} [Ip: {}]", instanceId, targetAgentIp);
                    stringRedisTemplate.opsForZSet().remove(GC_CONTAINER_KEY, member);

                } else {
                    // [Case C] 남의 것인데 아직 3분 안 지남: 주인이 처리하도록 기다림 (ZSet 삭제 안 함)
                    log.debug("Waiting for owner ({}) to clean up: {}", targetAgentIp, instanceId);
                }

            } catch (Exception e) {
                log.error("Failed to cleanup member: {}", member, e);
            }
        }
    }

    private void removeFromIdleQueue(String functionId, String instanceId, String agentIp) {
        String key = IDLE_QUEUE_PREFIX + functionId;

        // Redis에 저장된 것과 똑같은 SockPath 재조립
        String targetSockPath = String.format("instances/%s/%s/sock/function.sock", functionId, instanceId);

        try {
            // Redis에 저장된 것과 똑같은 JSON 객체 생성 (IP 포함)
            ContainerInfo targetInfo = new ContainerInfo(agentIp, targetSockPath);
            String jsonToRemove = objectMapper.writeValueAsString(targetInfo);

            // LREM: 리스트에서 정확히 일치하는 JSON 값을 찾아 삭제
            Long removedCount = stringRedisTemplate.opsForList().remove(key, 0, jsonToRemove);

            if (removedCount != null && removedCount > 0) {
                log.info("Removed from Redis Idle Queue: [IP:{}] {}", agentIp, instanceId);
            }
        } catch (Exception e) {
            log.error("Failed to remove JSON from Redis List: {}", instanceId, e);
        }
    }

    private void deleteContainerResources(String functionId, String instanceId) {
        log.info("Cleaning up LOCAL resources for Instance: {}", instanceId);

        try {
            containerPort.removeContainer(instanceId);
        } catch (Exception e) {
            log.warn("Docker remove failed: {}", e.getMessage());
        }

        try {
            Path instanceDir = Paths.get(workspacePath, "instances", functionId, instanceId);
            FileSystemUtils.deleteRecursively(instanceDir);
        } catch (Exception e) {
            log.warn("Filesystem remove failed: {}", e.getMessage());
        }
    }
}