package com.hanyahunya.registry.application.service;

import com.hanyahunya.registry.application.port.in.function.FunctionAuthUseCase;
import com.hanyahunya.registry.application.port.out.EncodeAdapterFactory;
import com.hanyahunya.registry.application.port.out.EncodePort;
import com.hanyahunya.registry.domain.model.EncodeType;
import com.hanyahunya.registry.domain.repository.FunctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FunctionAuthService implements FunctionAuthUseCase {
    private final FunctionRepository functionRepository;
    private final EncodeAdapterFactory encodeAdapterFactory;

    @Override
    @Transactional(readOnly = true)
    public Result authFunction(Command command) {
        EncodePort encodePort = encodeAdapterFactory.getAdapter(EncodeType.FUNCTION_KEY);

        return functionRepository.findById(command.functionId())
                .map(function -> {
                    boolean isValid = encodePort.matches(command.accessKey(), function.getAccessKey());
                    String s3Key = isValid ? function.getS3Key() : null;
                    return new Result(isValid, s3Key);
                })
                .orElse(new Result(false, null));
    }
}