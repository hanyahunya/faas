package com.hanyahunya.registry.adapter.out.analysis.node;

import com.hanyahunya.registry.application.port.out.MaliciousCodeCheckPort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public abstract class AbstractNodeMaliciousCodeCheckAdapter implements MaliciousCodeCheckPort {

    // Node.js 전용 차단 목록
    private static final List<String> NODE_BLACKLIST = List.of(
            "child_process",
            "fs.unlink",
            "fs.rm",
            "eval(",
            "process.exit",
            "process.kill"
    );

    @Override
    public boolean isSafe(String codeContent) {
        if (codeContent == null || codeContent.isBlank()) {
            return true;
        }
        for (String keyword : NODE_BLACKLIST) {
            if (codeContent.contains(keyword)) {
                log.warn("[{}] Malicious Node.js code detected: {}", getRuntime(), keyword);
                return false;
            }
        }
        return true;
    }
}