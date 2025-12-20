package com.hanyahunya.registry.application.port.out;

import com.hanyahunya.registry.domain.model.Runtime;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MaliciousCodeCheckAdapterFactory {
    private final Map<Runtime, MaliciousCodeCheckPort> adapters;

    public MaliciousCodeCheckAdapterFactory(List<MaliciousCodeCheckPort> ports) {
        this.adapters = ports.stream()
                .collect(Collectors.toMap(
                    MaliciousCodeCheckPort::getRuntime,
                        port -> port
                ));
    }

    public MaliciousCodeCheckPort getAdapter(Runtime runtime) {
        MaliciousCodeCheckPort adapter = adapters.get(runtime);
        if (adapter == null) {
            throw new IllegalArgumentException("unsupported type: " + runtime);
        }
        return adapter;
    }
}
