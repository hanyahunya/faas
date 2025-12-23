package com.hanyahunya.invoker.infra.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AgentChannelManager {

    // Key: Agent IP, Value: gRPC Channel
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    private static final int AGENT_PORT = 9094;

    public ManagedChannel getChannel(String agentIp) {
        return channelCache.computeIfAbsent(agentIp, this::createChannel);
    }

    private ManagedChannel createChannel(String ip) {
        log.info("Creating new gRPC Channel for Agent: {}", ip);

        // dns:/// 붙여서 서비스 디스커버리를 우회하고 강제로 IP/DNS 연결을 시도 <- eureka 떄문
        return ManagedChannelBuilder.forTarget("dns:///" + ip + ":" + AGENT_PORT)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    // 서버 종료시 모든채널정리
    @PreDestroy
    public void closeAllChannels() {
        log.info("Closing all Agent gRPC channels...");
        for (ManagedChannel channel : channelCache.values()) {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
            }
        }
    }
}