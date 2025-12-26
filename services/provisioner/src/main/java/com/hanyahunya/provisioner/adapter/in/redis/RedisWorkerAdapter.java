package com.hanyahunya.provisioner.adapter.in.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.provisioner.application.port.in.ContainerUseCase;
import com.hanyahunya.provisioner.application.port.out.ContainerOrchestrationPort;
import com.hanyahunya.provisioner.application.port.out.WorkerManagementPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisWorkerAdapter implements WorkerManagementPort {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ContainerUseCase containerUseCase;

    // [추가] 컨테이너 개수 확인용 포트 주입
    private final ContainerOrchestrationPort containerOrchestrationPort;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Future<?>> activeListenerFutures = new ArrayList<>();

    private volatile boolean isRunning = false;
    private Integer currentStart = null;
    private Integer currentEnd = null;

    // 전체 파티션 수 (순환용)
    private static final int TOTAL_PARTITIONS = 16384;
    // 과부하 기준 컨테이너 수
    private static final int MAX_CONTAINER_LIMIT = 50;

    @Override
    public synchronized void syncWorkers(int start, int end) {
        if (isRunning && currentStart != null && currentEnd != null && currentStart == start && currentEnd == end) {
            return;
        }

        log.info("Worker Assignment Updating: {} ~ {}", start, end);
        stopAllWorkers();

        isRunning = true;
        currentStart = start;
        currentEnd = end;

        List<Integer> allPartitions = IntStream.rangeClosed(start, end).boxed().toList();
        int batchSize = 50;

        for (int i = 0; i < allPartitions.size(); i += batchSize) {
            int toIndex = Math.min(i + batchSize, allPartitions.size());
            List<Integer> batch = allPartitions.subList(i, toIndex);

            Runnable listenerTask = createGroupListenerTask(batch);
            Future<?> future = executor.submit(listenerTask);
            activeListenerFutures.add(future);
        }

        log.info("Workers Started. Listener Threads: {}", activeListenerFutures.size());
    }

    @Override
    public synchronized void stopAllWorkers() {
        if (!isRunning && activeListenerFutures.isEmpty()) return;

        log.info("Stopping all listeners for rebalance...");
        isRunning = false;

        for (Future<?> future : activeListenerFutures) {
            future.cancel(true);
        }

        activeListenerFutures.clear();
        currentStart = null;
        currentEnd = null;
    }

    private Runnable createGroupListenerTask(List<Integer> partitions) {
        return () -> {
            List<String> keys = partitions.stream().map(p -> "func:request:queue:" + p).toList();
            String[] keysArray = keys.toArray(new String[0]);
            String threadName = Thread.currentThread().toString();

            while (isRunning) {
                try {
                    List<String> result = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
                        StringRedisConnection stringConn = (StringRedisConnection) connection;
                        return stringConn.bLPop(0, keysArray);
                    });

                    if (result != null && !result.isEmpty()) {
                        String queueName = result.get(0);
                        String messageJson = result.get(1);

                        // 비동기 처리
                        executor.submit(() -> processMessageAsync(queueName, messageJson));
                    }

                } catch (Exception e) {
                    // ignore
                }

                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            log.debug("Listener stopped: {}", threadName);
        };
    }

    private void processMessageAsync(String queueName, String messageJson) {
        try {
            // =========================================================
            // [핵심] 과부하 제어 및 파티션 넘기기 (Load Shedding)
            // =========================================================

            // 1. 내가 전체 파티션을 담당하는지 확인 (Solo Mode)
            boolean isSoloMode = (currentStart != null && currentEnd != null)
                    && (currentStart == 0 && currentEnd == (TOTAL_PARTITIONS - 1));

            // 2. 솔로 모드가 아닐 때만 과부하 체크
            if (!isSoloMode) {
                int currentCount = containerOrchestrationPort.getFunctionContainerCount();

                // 3. 임계치(50개)를 넘으면 다음 파티션으로 토스
                if (currentCount >= MAX_CONTAINER_LIMIT) {
                    int nextPartition = (currentEnd + 1) % TOTAL_PARTITIONS;
                    String targetQueue = "func:request:queue:" + nextPartition;

                    log.warn("Overload! (Count: {} >= {}). Offloading to Queue: {}",
                            currentCount, MAX_CONTAINER_LIMIT, targetQueue);

                    // 다시 Redis 큐에 넣음 (폭탄 돌리기)
                    redisTemplate.opsForList().rightPush(targetQueue, messageJson);
                    return; // 처리 중단하고 종료
                }
            }

            // =========================================================

            log.info("Processing Task asynchronously: {}", messageJson);

            ColdStartRequestDto dto = objectMapper.readValue(messageJson, ColdStartRequestDto.class);
            ContainerUseCase.Command command = new ContainerUseCase.Command(
                    UUID.fromString(dto.functionId()),
                    dto.s3Key()
            );

            containerUseCase.createAndRunContainer(command);

        } catch (Exception e) {
            log.error("Failed to process message: {}", messageJson, e);
        }
    }

    private record ColdStartRequestDto(String functionId, String s3Key) {}
}