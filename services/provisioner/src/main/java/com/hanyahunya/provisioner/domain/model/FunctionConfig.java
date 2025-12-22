package com.hanyahunya.provisioner.domain.model;

import java.util.Map;

public record FunctionConfig(
        Runtime runtime,
        Map<String, String> env
) {}