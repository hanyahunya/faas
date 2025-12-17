package com.hanyahunya.registry.application.port.in.auth;

public interface RegisterUserUseCase {
    void register(Command command);

    record Command(
            String email,
            String password
    ) {}
}
