package com.hanyahunya.invoker.domain.model;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
// functionId로 검색하고, 최신순으로 정렬하기위해 복합인덱스 추가
@CompoundIndexes({
        @CompoundIndex(name = "function_time_idx", def = "{'functionId': 1, 'requestStartTime': -1}")
})
@Document(collection = "execution_logs")
public class ExecutionLog {

    @Id
    private UUID id;

    private UUID functionId;

    private LocalDateTime requestStartTime; // 요청 시작 시간

    private boolean success;
    private long memoryUsage;
    private long durationMs;            // Agent에서 측정한 순수 실행 시간
    private long totalProcessingTimeMs; // Invoker가 요청받고 응답하기까지 걸린 시간

    private String logS3Key;

    private ExecutionType executionType;
    private long coldStartDurationMs;
}