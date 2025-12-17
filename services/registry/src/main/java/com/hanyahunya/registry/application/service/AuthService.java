package com.hanyahunya.registry.application.service;

import com.hanyahunya.registry.application.port.in.TokensResult;
import com.hanyahunya.registry.application.port.in.auth.LoginUseCase;
import com.hanyahunya.registry.application.port.out.PasswordEncodePort;
import com.hanyahunya.registry.domain.exception.LoginFailedException;
import com.hanyahunya.registry.domain.exception.UserCompromisedException;
import com.hanyahunya.registry.domain.model.User;
import com.hanyahunya.registry.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService implements LoginUseCase {
    private final UserRepository userRepository;
    private final TokenService tokenService;

    private final PasswordEncodePort encoder;

    @Override
    public TokensResult login(Command command) {
        String email = command.email();
        User user = userRepository.findByEmail(email).orElseThrow(LoginFailedException::new);
        if (!encoder.matches(command.password(), user.getPassword())) {
            throw new LoginFailedException();
        }
        return switch (user.getStatus()) {
            case ACTIVE -> tokenService.issueToken(user);
            case PENDING_APPROVAL -> throw new LoginFailedException(user.getStatus());
            case COMPROMISED -> throw new UserCompromisedException();
        };
    }
}
