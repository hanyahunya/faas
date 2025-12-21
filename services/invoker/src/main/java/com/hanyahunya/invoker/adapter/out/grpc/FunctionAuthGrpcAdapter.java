package com.hanyahunya.invoker.adapter.out.grpc;

import com.hanyahunya.grpc.FunctionAuthServiceGrpc;
import com.hanyahunya.grpc.VerifyAccessRequest;
import com.hanyahunya.grpc.VerifyAccessResponse;
import com.hanyahunya.invoker.application.port.out.FunctionAuthPort;
import net.devh.boot.grpc.client.inject.GrpcClient; // 패키지 확인
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FunctionAuthGrpcAdapter implements FunctionAuthPort {

    @GrpcClient("registry-service")
    private FunctionAuthServiceGrpc.FunctionAuthServiceBlockingStub blockingStub;

    @Override
    public Result authenticateFunction(UUID functionId, String accessKey) {
        VerifyAccessRequest request = VerifyAccessRequest.newBuilder()
                .setFunctionId(functionId.toString())
                .setAccessKey(accessKey)
                .build();
        VerifyAccessResponse response;
        try {
            response = blockingStub.verifyAccess(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify access: " + e.getMessage());
        }
        return new Result(
                response.getIsValid(),
                response.getS3Key()
                );
    }
}