package com.hanyahunya.registry.adapter.out.analysis.java;

import com.hanyahunya.registry.domain.model.Runtime;
import org.springframework.stereotype.Component;

@Component
public class Java17MaliciousCodeCheckAdapter extends AbstractJavaMaliciousCodeCheckAdapter{
    @Override
    public Runtime getRuntime() {
        return Runtime.JAVA_17;
    }
}
