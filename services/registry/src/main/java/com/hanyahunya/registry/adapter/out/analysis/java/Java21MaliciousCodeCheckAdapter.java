package com.hanyahunya.registry.adapter.out.analysis.java;

import com.hanyahunya.registry.domain.model.Runtime;

public class Java21MaliciousCodeCheckAdapter extends AbstractJavaMaliciousCodeCheckAdapter{
    @Override
    public Runtime getRuntime() {
        return Runtime.JAVA_21;
    }
}
