package com.hanyahunya.registry.adapter.out.security;

import com.hanyahunya.registry.application.port.out.EncodePort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public abstract class AbstractSha256EncodeAdapter implements EncodePort {

    private final String secret;
    private static final String ALGORITHM = "HmacSHA256";

    protected AbstractSha256EncodeAdapter(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Secret key cannot be empty");
        }
        this.secret = secret;
    }

    @Override
    public String encode(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(secretKeySpec);
            return bytesToHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 error", e);
        }
    }

    @Override
    public boolean matches(String data, String hashedData) {
        return encode(data).equals(hashedData);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}