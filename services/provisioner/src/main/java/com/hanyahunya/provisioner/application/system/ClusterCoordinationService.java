package com.hanyahunya.provisioner.application.system;

import com.hanyahunya.provisioner.application.port.out.ClusterStatePort;
import com.hanyahunya.provisioner.application.port.out.WorkerManagementPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class ClusterCoordinationService {

    private final ClusterStatePort clusterStatePort;
    private final WorkerManagementPort workerManagementPort;

    private final String myNodeId = UUID.randomUUID().toString();
    private static final int TOTAL_PARTITIONS = 16384;

    @PostConstruct
    public void init() {
        log.info("Provisioner Node Started. ID: {}", myNodeId);
        heartbeat();
    }

    @Scheduled(fixedRate = 3000)
    public void heartbeat() {
        clusterStatePort.sendHeartbeat(myNodeId);
    }

    @Scheduled(fixedRate = 5000)
    public void leaderTask() {
        boolean isLeader = clusterStatePort.isLeader(myNodeId);

        if (!isLeader) {
            if (clusterStatePort.tryAcquireLeadership(myNodeId)) {
                log.info("I am the NEW LEADER! [{}]", myNodeId);
                isLeader = true;
            }
        } else {
            clusterStatePort.extendLeadership(myNodeId);
        }

        if (isLeader) {
            rebalancePartitions();
        }
    }

    private void rebalancePartitions() {
        List<String> activeNodes = new ArrayList<>(clusterStatePort.getActiveNodes());
        Collections.sort(activeNodes);

        int nodeCount = activeNodes.size();
        if (nodeCount == 0) return;

        int chunkSize = TOTAL_PARTITIONS / nodeCount;
        int remainder = TOTAL_PARTITIONS % nodeCount;
        int start = 0;

        for (int i = 0; i < nodeCount; i++) {
            String nodeId = activeNodes.get(i);
            int mySize = chunkSize + (i < remainder ? 1 : 0);
            int end = start + mySize - 1;

            clusterStatePort.saveAssignmentRange(nodeId, start, end);
            start = end + 1;
        }
    }

    @Scheduled(fixedRate = 5000)
    public void syncWorkers() {
        ClusterStatePort.Range range = clusterStatePort.getAssignmentRange(myNodeId);

        if (range != null && range.isValid()) {
            // 어댑터에게 명령 전달
            workerManagementPort.syncWorkers(range.start(), range.end());
        } else {
            workerManagementPort.stopAllWorkers();
        }
    }
}