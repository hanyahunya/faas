package com.hanyahunya.provisioner.adapter.in.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.provisioner.application.port.in.ContainerUseCase;
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

    // 가상 스레드 실행기 (redis리스너와 실제 비즈니스 로직 모두 여기서 생성되지만 서로 독립적임)
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // 리스너 스레드의 핸들만 관리 (실제 작업 중인 워커스레드는 여기포함 안됨)
    private final List<Future<?>> activeListenerFutures = new ArrayList<>();

    private volatile boolean isRunning = false;
    private Integer currentStart = null;
    private Integer currentEnd = null;

    @Override
    public synchronized void syncWorkers(int start, int end) {
        if (isRunning && currentStart != null && currentEnd != null && currentStart == start && currentEnd == end) {
            return;
        }

        log.info("Worker Assignment Updating: {} ~ {}", start, end);
        stopAllWorkers(); // 기존 리스너만 종료 (실행 중인 컨테이너 생성 작업은 계속 돔)

        isRunning = true;
        currentStart = start;
        currentEnd = end;

        List<Integer> allPartitions = IntStream.rangeClosed(start, end).boxed().toList();
        int batchSize = 50;

        for (int i = 0; i < allPartitions.size(); i += batchSize) {
            int toIndex = Math.min(i + batchSize, allPartitions.size());
            List<Integer> batch = allPartitions.subList(i, toIndex);

            // 리스너 실행 (이 Future만 리스트에 저장)
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

        // 리스너 스레드에게만 "그만 가져오고 퇴근해"라고 인터럽트 걺 <- Provisioner 아웃스케일로 인한 파티션 재분재
        for (Future<?> future : activeListenerFutures) {
            future.cancel(true);
        }

        // 리스트 비움 (실제 작업중인 스레드는 executor 어딘가에서 잘돌고 있음)
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
                    // 데이터 가져오기 (Blocking)
                    List<String> result = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
                        StringRedisConnection stringConn = (StringRedisConnection) connection;
                        return stringConn.bLPop(0, keysArray);
                    });

                    // 데이터가 있을경우
                    if (result != null && !result.isEmpty()) {
                        String queueName = result.get(0);
                        String messageJson = result.get(1);

                        // 처리담당 스레드에 토스하고 나는 즉시빠져나옴
                        // 이 Future는 activeListenerFutures에 저장하지 않으므로, stopAllWorkers()의 영향을 안받음.
                        executor.submit(() -> processMessageAsync(queueName, messageJson));
                    }

                } catch (Exception e) {
                    // 인터럽트 발생 시 or Redis 에러 시
                }

                // 종료신호 왔으면 즉시 루프탈출
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            log.debug("Listener stopped: {}", threadName);
        };
    }

    // 이 메서드는 리스너와는 완전히 별개인 익명 가상스레드에서 실행됨
    private void processMessageAsync(String queueName, String messageJson) {
        try {
            log.info("Processing Task asynchronously: {}", messageJson);

            ColdStartRequestDto dto = objectMapper.readValue(messageJson, ColdStartRequestDto.class);
            ContainerUseCase.Command command = new ContainerUseCase.Command(
                    UUID.fromString(dto.functionId()),
                    dto.s3Key()
            );

            containerUseCase.createAndRunContainer(command); // 무거운 작업

        } catch (Exception e) {
            log.error("Failed to process message: {}", messageJson, e);
        }
    }

    private record ColdStartRequestDto(String functionId, String s3Key) {}
}