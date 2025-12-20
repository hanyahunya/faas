package com.hanyahunya.registry.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.registry.application.port.in.function.RegisterFunctionUseCase;
import com.hanyahunya.registry.application.port.out.*;
import com.hanyahunya.registry.domain.model.EncodeType;
import com.hanyahunya.registry.domain.model.Function;
import com.hanyahunya.registry.domain.model.Runtime;
import com.hanyahunya.registry.domain.repository.FunctionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FunctionRegisterService implements RegisterFunctionUseCase {
    private final FunctionRepository functionRepository;
    private final SourceStoragePort sourceStoragePort;
    private final MaliciousCodeCheckAdapterFactory maliciousCodeCheckAdapterFactory;
    private final ObjectMapper objectMapper;
    private final EncodeAdapterFactory encodeFactory;

    @Override
    @Transactional
    public Result register(Command command) {
        MaliciousCodeCheckPort maliciousCodeCheckPort = maliciousCodeCheckAdapterFactory.getAdapter(command.runtime());
        EncodePort encodePort = encodeFactory.getAdapter(EncodeType.FUNCTION_KEY);

        if (!maliciousCodeCheckPort.isSafe(command.codeContent())) {
            throw new IllegalArgumentException("Malicious code detected.");
        }

//        Function function = functionRepository.findByUserIdAndName(command.userId(), command.functionName())
//                .orElse(null);
        UUID userId = command.userId();
        UUID functionId = UUID.randomUUID();
        String s3Key = "functions/" + userId + "/" + functionId + ".zip";

        // Zip 아티팩트 생성
        byte[] zipBytes = createZipArtifact(functionId, command);

        // S3 업로드
        sourceStoragePort.upload(zipBytes, s3Key);

        // 함수 실행용 키 저장
        String accessKey = UUID.randomUUID().toString().replace("-", "");

        Function function = Function.builder()
                .functionId(functionId)
                .userId(userId)
                .accessKey(encodePort.encode(accessKey))
                .functionName(command.functionName())
                .description(command.description())
                .runtime(command.runtime())
                .s3Key(s3Key)
                .build();

        functionRepository.save(function);
        log.info("Function Registered: {} (ID: {})", command.functionName(), functionId);
        return new Result(functionId, accessKey);
    }

    private byte[] createZipArtifact(UUID functionId, Command command) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            // Manifest 생성
            Map<String, Object> manifest = new HashMap<>();
//            manifest.put("id", functionId.toString());
//            manifest.put("name", command.functionName());
            manifest.put("runtime", command.runtime());
            manifest.put("env", command.envVars());

            // manifest.json 추가
            zos.putNextEntry(new ZipEntry("config.json"));
            zos.write(objectMapper.writeValueAsBytes(manifest));
            zos.closeEntry();

            // 코드 파일 추가
            String codeFileName = getFileNameByRuntime(command.runtime());
            zos.putNextEntry(new ZipEntry(codeFileName));
            zos.write(command.codeContent().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create zip artifact", e);
        }
    }

    private String getFileNameByRuntime(Runtime runtime) {
        String name = runtime.name();
        if (name.startsWith("PYTHON")) return "main.py";
        if (name.startsWith("JAVA")) return "Main.java";
        if (name.startsWith("NODE")) return "index.js";
        throw new IllegalArgumentException("Unknown runtime type: " + runtime);
    }
}
