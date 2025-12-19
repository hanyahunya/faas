package com.hanyahunya.registry.adapter.out.analysis.python;

import com.hanyahunya.registry.application.port.out.MaliciousCodeCheckPort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public abstract class AbstractPythonMaliciousCodeCheckAdapter implements MaliciousCodeCheckPort {

    // Python 전용 차단 목록
    private static final List<String> PYTHON_BLACKLIST = List.of(
            "os.system",
            "subprocess.call",
            "subprocess.Popen",
            "eval(",
            "exec(",
            "import os",
            "import subprocess"
    );

    @Override
    public boolean isSafe(String codeContent) {
        if (codeContent == null || codeContent.isBlank()) {
            return true;
        }
        // Python은 아직 AST 파서가 없으므로 단순 키워드 검사
        for (String keyword : PYTHON_BLACKLIST) {
            if (codeContent.contains(keyword)) {
                log.warn("[{}] Malicious Python code detected: {}", getRuntime(), keyword);
                return false;
            }
        }
        return true;
    }
}