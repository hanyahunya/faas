package com.hanyahunya.registry.application.port.out;

public interface TokenEncodePort {
    String encode(String data);
    boolean matches(String data, String hashedData);
}
