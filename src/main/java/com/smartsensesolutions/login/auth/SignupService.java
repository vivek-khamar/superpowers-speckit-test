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

        // Deliberately confirms account existence via a distinct 409, unlike
        // LoginService's enumeration-resistant 401 (same status/body/timing
        // whether the email is unknown or the password is wrong). Accepted
        // tradeoff, not an oversight: FR-5 requires this exact response, it
        // matches standard signup UX across virtually all major sites, and
        // closing it would need an out-of-scope email-verification flow this
        // codebase doesn't have. Lower severity than login-enumeration too --
        // this doesn't help a credential-stuffing attack the way confirming
        // a *valid password* would, only that an address is registered.
        if (userRepository.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new EmailAlreadyExistsException();
        }

        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.name());

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            if (!isEmailUniqueConstraintViolation(e)) {
                throw e;
            }
            throw new EmailAlreadyExistsException();
        }

        return SignupResponse.success(user);
    }

    private boolean isEmailUniqueConstraintViolation(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("ux_users_email")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
