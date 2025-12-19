package com.hanyahunya.registry.adapter.in.web;

import com.hanyahunya.registry.adapter.in.web.dto.FunctionRegisterRequest;
import com.hanyahunya.registry.adapter.in.web.dto.FunctionRegisterResponse;
import com.hanyahunya.registry.application.port.in.function.RegisterFunctionUseCase;
import com.hanyahunya.registry.infra.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/function")
@RequiredArgsConstructor
public class FunctionController {

    private final RegisterFunctionUseCase registerFunctionUseCase;

    @PostMapping
    public ResponseEntity<FunctionRegisterResponse> registerFunction(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody FunctionRegisterRequest request
            ) {
        UUID userId = user.getUserId();
        UUID functionId = registerFunctionUseCase.register(request.toCommand(userId));
        return ResponseEntity.ok(
                new FunctionRegisterResponse(functionId.toString(), "Function registered")
        );
    }
}
