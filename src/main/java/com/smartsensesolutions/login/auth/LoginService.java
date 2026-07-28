package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;
import com.smartsensesolutions.login.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    // A real, syntactically valid BCrypt hash of an arbitrary string that is
    // never a real password. Compared against on every login where the email
    // doesn't match a real user, so an unknown-email attempt costs the same
    // BCrypt computation as a wrong-password attempt on a real account —
    // otherwise the two cases would be distinguishable by response time.
    static final String DUMMY_HASH =
            "$2y$10$tcqCU9d4fhTLgFvvjH4QoOaY7jQZIX45nkCVWOYI7Bcte7sPCGxbS";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRequestValidator validator;
    private final JwtService jwtService;
    private final int maxAttempts;
    private final Duration lockoutDuration;

    public LoginService(UserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         LoginRequestValidator validator,
                         JwtService jwtService,
                         @Value("${login.lockout.max-attempts:5}") int maxAttempts,
                         @Value("${login.lockout.duration-minutes:15}") long lockoutDurationMinutes) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.jwtService = jwtService;
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = Duration.ofMinutes(lockoutDurationMinutes);
    }

    public LoginResult login(LoginRequest request) {
        List<String> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new LoginValidationException(violations);
        }

        Instant now = Instant.now();
        Optional<User> maybeUser = userRepository.findByEmailIgnoreCase(request.email());

        if (maybeUser.isPresent() && maybeUser.get().isLockedOut(now)) {
            throw new AccountLockedException();
        }

        String hashToCheck = maybeUser.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean matches = passwordEncoder.matches(request.password(), hashToCheck);

        if (matches && maybeUser.isPresent()) {
            User user = maybeUser.get();
            user.recordSuccess();
            userRepository.save(user);
            String token = jwtService.generateToken(user.getId(), List.of("USER"));
            return new LoginResult(LoginResponse.success(user), token);
        }

        if (maybeUser.isPresent()) {
            User user = maybeUser.get();
            user.recordFailure(now, maxAttempts, lockoutDuration);
            userRepository.save(user);
            if (user.isLockedOut(now)) {
                log.warn("Identity {} locked out after {} consecutive failed login attempts",
                        maskIdentity(request.email()), user.getFailedAttempts());
            } else {
                log.warn("Failed login attempt for identity {}", maskIdentity(request.email()));
            }
        } else {
            log.warn("Failed login attempt for identity {}", maskIdentity(request.email()));
        }
        throw new AuthenticationFailedException();
    }

    // Never logs the raw password. Masks the identity so a log stream isn't
    // itself a plaintext record of every email address that attempted login.
    private String maskIdentity(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
