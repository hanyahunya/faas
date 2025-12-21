package com.hanyahunya.invoker.adapter.in.web.dto;

import java.util.Map;
import java.util.UUID;

public record InvokeRequest(
        UUID functionId,
        String accessKey,
        Map<String, Object> params
) {}