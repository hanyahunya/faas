package com.hanyahunya.registry.adapter.out.security;

import com.hanyahunya.registry.application.port.out.PasswordEncodePort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptPasswordEncodeAdapter implements PasswordEncodePort {
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public String encode(String data) {
        return passwordEncoder.encode(data);
    }

    @Override
    public boolean matches(String data, String hashedData) {
        return passwordEncoder.matches(data, hashedData);
    }
}
