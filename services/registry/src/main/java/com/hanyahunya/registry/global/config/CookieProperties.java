package com.hanyahunya.registry.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cookie")
public record CookieProperties(
        String domain,
        boolean secure,
        String sameSite,
        String path
) {}