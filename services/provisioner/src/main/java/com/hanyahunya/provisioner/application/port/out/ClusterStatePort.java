package com.hanyahunya.provisioner.application.port.out;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Set;

public interface ClusterStatePort {
    // [Heartbeat]
    void sendHeartbeat(String nodeId);

    // [Cluster View] 현재 살아있는 모든노드조회
    Set<String> getActiveNodes();

    // [Leader] 리더 선출 시도 - 성공시 true
    boolean tryAcquireLeadership(String nodeId);

    // [Leader] 리더 권한 연장
    void extendLeadership(String nodeId);

    // [Leader] 현재 리더인지 확인
    boolean isLeader(String nodeId);

    // [Assignment] 특정 노드에게 파티션 범위(Start, End) 할당
    void saveAssignmentRange(String nodeId, int start, int end);

    // [Assignment] 나에게 할당된 범위 조회 (없으면 null or empty)
    Range getAssignmentRange(String nodeId);

    // 범위 표현용
    record Range(int start, int end) {
        @JsonIgnore
        public boolean isValid() { return start >= 0 && end >= start; }
    }
}