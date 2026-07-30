package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;
import com.smartsensesolutions.login.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SignupService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SignupRequestValidator validator;

    public SignupService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          SignupRequestValidator validator) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
    }

    public SignupResponse signup(SignupRequest request) {
        List<String> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new SignupValidationException(violations);
        }

        if (userRepository.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new EmailAlreadyExistsException();
        }

        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.name());

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyExistsException();
        }

        return SignupResponse.success(user);
    }
}
