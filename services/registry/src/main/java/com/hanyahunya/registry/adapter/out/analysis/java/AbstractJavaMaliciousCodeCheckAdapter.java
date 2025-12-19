package com.hanyahunya.registry.adapter.out.analysis.java;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.hanyahunya.registry.application.port.out.MaliciousCodeCheckPort;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public abstract class AbstractJavaMaliciousCodeCheckAdapter implements MaliciousCodeCheckPort {

    // Java 공통 차단 목록
    private static final Set<String> BLOCKED_JAVA_CALLS = Set.of(
            "System.exit",
            "Runtime.getRuntime",
            "ProcessBuilder.start",
            "System.setSecurityManager"
    );

    @Override
    public boolean isSafe(String codeContent) {
        if (codeContent == null || codeContent.isBlank()) {
            return true;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(codeContent);
            SecurityVisitor visitor = new SecurityVisitor();
            cu.accept(visitor, null);

            if (visitor.hasMaliciousCall()) {
                log.warn("[{}] Malicious Java code detected: {}", getRuntime(), visitor.getDetectedReason());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[{}] Java code parsing error. Fallback to simple check.", getRuntime(), e);
            // 파싱 실패 시 단순 키워드 검사로 대체하거나 false 처리
            return checkByKeywords(codeContent);
        }
    }

    private boolean checkByKeywords(String code) {
        return BLOCKED_JAVA_CALLS.stream().noneMatch(code::contains);
    }

    private static class SecurityVisitor extends VoidVisitorAdapter<Void> {
        private boolean malicious = false;
        @Getter
        private String detectedReason = "";

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            String callSignature = n.getScope().map(s -> s.toString() + ".").orElse("") + n.getNameAsString();
            if (BLOCKED_JAVA_CALLS.stream().anyMatch(callSignature::contains)) {
                malicious = true;
                detectedReason = "Blocked call: " + callSignature;
            }
            super.visit(n, arg);
        }

        public boolean hasMaliciousCall() {
            return malicious;
        }
    }
}