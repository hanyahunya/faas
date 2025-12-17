package com.hanyahunya.registry.application.service;

import com.hanyahunya.registry.application.port.in.auth.RegisterUserUseCase;
import com.hanyahunya.registry.application.port.out.PasswordEncodePort;
import com.hanyahunya.registry.domain.exception.EmailAlreadyExistsException;
import com.hanyahunya.registry.domain.model.User;
import com.hanyahunya.registry.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegisterService implements RegisterUserUseCase {
    private final UserRepository userRepository;

    private final PasswordEncodePort encoder;

    @Override
    @Transactional
    public void register(Command command) {
        String email = command.email();
        // Bloom filter 써볼까
        userRepository.findByEmail(email).ifPresent(
                user -> {
                    throw new EmailAlreadyExistsException();
                });
        User user = User.builder()
                .email(email)
                .password(encoder.encode(command.password()))
                .build();
        userRepository.save(user);
    }
}
