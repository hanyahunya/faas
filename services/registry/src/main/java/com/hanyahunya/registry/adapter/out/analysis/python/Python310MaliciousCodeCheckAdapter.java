package com.hanyahunya.registry.adapter.out.analysis.python;

import com.hanyahunya.registry.domain.model.Runtime;
import org.springframework.stereotype.Component;

@Component
public class Python310MaliciousCodeCheckAdapter extends AbstractPythonMaliciousCodeCheckAdapter{
    @Override
    public Runtime getRuntime() {
        return Runtime.PYTHON_3_10;
    }
}
