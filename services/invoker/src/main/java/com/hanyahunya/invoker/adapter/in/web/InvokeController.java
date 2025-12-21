package com.hanyahunya.invoker.adapter.in.web;

import com.hanyahunya.invoker.adapter.in.web.dto.InvokeRequest;
import com.hanyahunya.invoker.application.port.in.InvokeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InvokeController {

    private final InvokeUseCase invokeUseCase;

    @PostMapping("/invoke")
    public ResponseEntity<InvokeUseCase.Result> invokeFunction(@RequestBody InvokeRequest request) {
        InvokeUseCase.Command command = new InvokeUseCase.Command(
                request.functionId(),
                request.accessKey(),
                request.params()
        );

        return ResponseEntity.ok(invokeUseCase.invoke(command));
    }


}