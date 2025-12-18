package com.hanyahunya.registry.application.port.in.auth;

import com.hanyahunya.registry.application.port.in.TokensResult;

public interface LoginUseCase {
    TokensResult login(Command command);

    record Command(
            String email,
            String password
    ){}
}
