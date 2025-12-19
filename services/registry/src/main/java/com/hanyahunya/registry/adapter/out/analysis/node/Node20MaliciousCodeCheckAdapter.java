package com.hanyahunya.registry.adapter.out.analysis.node;

import com.hanyahunya.registry.domain.model.Runtime;
import org.springframework.stereotype.Component;

@Component
public class Node20MaliciousCodeCheckAdapter extends AbstractNodeMaliciousCodeCheckAdapter{
    @Override
    public Runtime getRuntime() {
        return Runtime.NODE_20;
    }
}
