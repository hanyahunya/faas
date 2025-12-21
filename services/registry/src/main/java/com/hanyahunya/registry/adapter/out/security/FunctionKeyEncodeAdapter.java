package com.hanyahunya.registry.adapter.out.security;

import com.hanyahunya.registry.domain.model.EncodeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FunctionKeyEncodeAdapter extends AbstractSha256EncodeAdapter {
    public FunctionKeyEncodeAdapter(@Value("${encoder.function-secret}") String secret) {
        super(secret);
    }

    @Override
    public EncodeType getEncodeType() {
        return EncodeType.FUNCTION_KEY;
    }
}