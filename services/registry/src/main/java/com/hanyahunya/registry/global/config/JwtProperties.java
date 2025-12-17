package com.hanyahunya.registry.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String accessTokenSecret,
        String refreshTokenSecret,
        Duration accessTokenExpiration,
        Duration refreshTokenExpiration
) {}