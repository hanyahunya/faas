package com.hanyahunya.registry.application.port.out;

import com.hanyahunya.registry.domain.model.EncodeType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class EncodeAdapterFactory {
    private final Map<EncodeType, EncodePort> adapters;

    public EncodeAdapterFactory(List<EncodePort> ports) {
        this.adapters = ports.stream()
                .collect(Collectors.toMap(
                        EncodePort::getEncodeType,
                        port -> port
                ));
    }

    public EncodePort getAdapter(EncodeType encodeType) {
        EncodePort adapter = adapters.get(encodeType);
        if (adapter == null) {
            throw new IllegalArgumentException("unsupported type: " + encodeType);
        }
        return adapter;
    }
}
