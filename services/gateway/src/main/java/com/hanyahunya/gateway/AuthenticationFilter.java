package com.hanyahunya.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationFilter implements WebFilter {
    
    @Value("${jwt.access-token-secret}")
    private String accessSecret;

    private SecretKey accessKey;

    private static final String[] WHITELIST = {
            "/auth/",
            "/invoke",
            "/actuator/health"
    };

    @PostConstruct
    public void init() {
        accessKey = Keys.hmacShaKeyFor(accessSecret.getBytes(StandardCharsets.UTF_8));
    }

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private static final String BLACKLIST_KEY_PREFIX = "blacklist:user:";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod().name();
        String clientIp = getClientIp(request); // IP 추출 메서드 사용

        // 1. [요청 진입 로그] 모든 요청의 출발지 기록
        log.info(">> [Request] IP=[{}], Method=[{}], Path=[{}]", clientIp, method, path);

        for (String allowedPath : WHITELIST) {
            if (path.startsWith(allowedPath)) {
                return chain.filter(exchange);
            }
        }

        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            return onError(exchange, "Authorization 헤더가 없습니다.");
        }

        String authorizationHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return onError(exchange, "Authorization 헤더 형식이 올바르지 않습니다.");
        }
        String token = authorizationHeader.substring(7);

        return Mono.fromCallable(() -> parseJwt(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(claims -> {
                    String userId = claims.getSubject();
                    String role = claims.get("role", String.class);

                    return reactiveRedisTemplate.opsForValue().get(BLACKLIST_KEY_PREFIX + userId)
                            .flatMap(compromisedAtStr -> {
                                long compromisedAt = Long.parseLong(compromisedAtStr);
                                long tokenIssuedAt = claims.getIssuedAt().getTime() / 1000L;

                                if (tokenIssuedAt < compromisedAt) {
                                    return onError(exchange, "무효화된 토큰입니다. 사용자ID: " + userId);
                                }

                                if (path.startsWith("/token/verify")) {
                                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                                    return exchange.getResponse().setComplete();
                                }

                                return passThrough(exchange, chain, userId, role);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                if (path.startsWith("/token/verify")) {
                                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                                    return exchange.getResponse().setComplete();
                                }
                                return passThrough(exchange, chain, userId, role);
                            }));
                })
                .onErrorResume(e -> onError(exchange, "유효하지 않은 토큰입니다. (" + e.getMessage() + ")"));
    }

    private Mono<Void> passThrough(ServerWebExchange exchange, WebFilterChain chain, String userId, String role) {
        ServerHttpRequest request = exchange.getRequest();
        String clientIp = getClientIp(request);
        String path = request.getURI().getPath();

        ServerHttpRequest newRequest = request.mutate()
                .header("X-User-Id", userId)
                .header("X-User-Role", role)
                .build();

        // 2. [인증 성공 로그] 누가 어디서 통과했는지 기록
        log.info("<< [Auth Success] UserID=[{}], IP=[{}], Path=[{}]", userId, clientIp, path);

        return chain.filter(exchange.mutate().request(newRequest).build())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, null))
                );
    }

    private Claims parseJwt(String token) {
        return Jwts.parser()
                .verifyWith(accessKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err) {
        ServerHttpRequest request = exchange.getRequest();
        String clientIp = getClientIp(request);
        String path = request.getURI().getPath();

        // 3. [인증 실패 로그] 에러 원인과 요청자 정보 기록
        log.error("!! [Auth Failed] Reason=[{}], IP=[{}], Path=[{}]", err, clientIp, path);

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /**
     * 클라이언트 실제 IP 추출 (Nginx 등 프록시 대응)
     */
    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeaders().getFirst("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeaders().getFirst("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() && request.getRemoteAddress() != null) {
            ip = request.getRemoteAddress().getAddress().getHostAddress();
        }
        return ip;
    }
}