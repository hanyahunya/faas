package com.hanyahunya.provisioner.application.port.out;

public interface WorkerManagementPort {
    // 특정 범위의 파티션에 대해 리스너를 동기화(실행/종료)
    void syncWorkers(int start, int end);

    // 모든 리스너 중지
    void stopAllWorkers();
}