package com.hanyahunya.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "user")
public record EurekaSecurityProperties(
        User admin,
        User gateway,
        User invoker,
        User register,
        User provisioner
) {
    public record User(String username, String password) {}
}