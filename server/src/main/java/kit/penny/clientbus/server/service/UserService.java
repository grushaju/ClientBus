package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.user.ChangePasswordRequest;
import kit.penny.clientbus.common.dto.user.CreateUserRequest;
import kit.penny.clientbus.common.dto.user.UserDto;
import kit.penny.clientbus.server.mapper.UserMapper;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            UserMapper userMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UserEntity createUser(
            String login,
            String password
    ) {

        if (userRepository.existsByLogin(login)) {
            throw new IllegalArgumentException(
                    "User with login already exists: " + login
            );
        }

        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "Password cannot be empty"
            );
        }

        String passwordHash =
                passwordEncoder.encode(password);

        UserEntity user = new UserEntity();

        user.setLogin(login);
        user.setPasswordHash(passwordHash);
        user.setEnabled(true);

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserDto getUser(UUID id) {

        UserEntity user =
                userRepository.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "User not found: " + id
                                )
                        );

        return userMapper.toDto(user);
    }

    public void changePassword(
            UUID id,
            ChangePasswordRequest request
    ) {

        UserEntity user =
                userRepository.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "User not found: " + id
                                )
                        );

        if (!passwordEncoder.matches(
                request.currentPassword(),
                user.getPasswordHash()
        )) {
            throw new IllegalArgumentException(
                    "Current password is incorrect"
            );
        }

        user.setPasswordHash(
                passwordEncoder.encode(
                        request.newPassword()
                )
        );
    }

    public void setEnabled(
            UUID id,
            boolean enabled
    ) {

        UserEntity user =
                userRepository.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "User not found: " + id
                                )
                        );

        user.setEnabled(enabled);
    }
}