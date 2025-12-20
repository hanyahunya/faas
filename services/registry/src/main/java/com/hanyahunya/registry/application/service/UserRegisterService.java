package com.hanyahunya.registry.application.service;

import com.hanyahunya.registry.application.port.in.auth.RegisterUserUseCase;
import com.hanyahunya.registry.application.port.out.EncodeAdapterFactory;
import com.hanyahunya.registry.application.port.out.EncodePort;
import com.hanyahunya.registry.domain.exception.EmailAlreadyExistsException;
import com.hanyahunya.registry.domain.model.EncodeType;
import com.hanyahunya.registry.domain.model.User;
import com.hanyahunya.registry.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserRegisterService implements RegisterUserUseCase {
    private final UserRepository userRepository;
    private final EncodeAdapterFactory encodeFactory;

    @Override
    @Transactional
    public void register(Command command) {
        EncodePort encoder = encodeFactory.getAdapter(EncodeType.PASSWORD);

        String email = command.email();
        // Bloom filter 써볼까
        userRepository.findByEmail(email).ifPresent(
                user -> {
                    throw new EmailAlreadyExistsException();
                });
        User user = User.signupBuilder()
                .email(email)
                .password(encoder.encode(command.password()))
                .build();
        userRepository.save(user);
    }
}
