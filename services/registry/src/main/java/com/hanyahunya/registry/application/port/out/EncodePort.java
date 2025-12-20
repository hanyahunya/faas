package com.hanyahunya.registry.application.port.out;

import com.hanyahunya.registry.domain.model.EncodeType;

public interface EncodePort {
    String encode(String data);
    boolean matches(String data, String hashedData);
    EncodeType getEncodeType();
}
