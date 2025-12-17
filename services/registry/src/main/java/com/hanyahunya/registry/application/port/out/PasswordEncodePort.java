package com.hanyahunya.registry.application.port.out;

public interface PasswordEncodePort {
    String encode(String data);
    boolean matches(String data, String hashedData);
}
