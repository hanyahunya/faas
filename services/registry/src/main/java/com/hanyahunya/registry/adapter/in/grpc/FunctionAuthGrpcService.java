package com.hanyahunya.registry.adapter.in.grpc;

import com.hanyahunya.grpc.FunctionAuthServiceGrpc;
import com.hanyahunya.grpc.VerifyAccessRequest;
import com.hanyahunya.grpc.VerifyAccessResponse;
import com.hanyahunya.registry.application.port.in.function.FunctionAuthUseCase;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;

import java.util.UUID;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class FunctionAuthGrpcService extends FunctionAuthServiceGrpc.FunctionAuthServiceImplBase {
    private final FunctionAuthUseCase functionAuthUseCase;

    @Override
    public void verifyAccess(VerifyAccessRequest request, StreamObserver<VerifyAccessResponse> responseObserver) {
        boolean isValid = false;
        String s3Key = "";

        try {
            UUID functionId = UUID.fromString(request.getFunctionId());

            FunctionAuthUseCase.Result result = functionAuthUseCase.authFunction(
                    new FunctionAuthUseCase.Command(functionId, request.getAccessKey())
            );

            isValid = result.isValid();
            s3Key = result.s3Key() != null ? result.s3Key() : "";

        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID format in gRPC request: {}", request.getFunctionId());
        } catch (Exception e) {
            log.error("Error during function access verification", e);
        }

        VerifyAccessResponse response = VerifyAccessResponse.newBuilder()
                .setIsValid(isValid)
                .setS3Key(s3Key)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}