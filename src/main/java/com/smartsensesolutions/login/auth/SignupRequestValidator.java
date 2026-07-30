package com.smartsensesolutions.login.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SignupRequestValidator {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final String SPECIAL_CHARACTERS = "!@#$%^&*()_+-=[]{};':\"\\|,.<>/?";

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 255;

    public List<String> validate(SignupRequest request) {
        List<String> violations = new ArrayList<>();

        if (isBlank(request.name())) {
            violations.add("name is required");
        } else if (request.name().length() > MAX_NAME_LENGTH) {
            violations.add("name must be at most " + MAX_NAME_LENGTH + " characters");
        }

        if (isBlank(request.email())) {
            violations.add("email is required");
        } else {
            if (!EMAIL_PATTERN.matcher(request.email()).matches()) {
                violations.add("email must be a valid email address");
            }
            if (request.email().length() > MAX_EMAIL_LENGTH) {
                violations.add("email must be at most " + MAX_EMAIL_LENGTH + " characters");
            }
        }

        if (isBlank(request.password())) {
            violations.add("password is required");
        } else {
            violations.addAll(passwordViolations(request.password()));
        }

        return violations;
    }

    private List<String> passwordViolations(String password) {
        List<String> violations = new ArrayList<>();

        if (password.length() < MIN_PASSWORD_LENGTH) {
            violations.add("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            violations.add("password must contain at least 1 uppercase letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            violations.add("password must contain at least 1 digit");
        }
        if (password.chars().noneMatch(c -> SPECIAL_CHARACTERS.indexOf(c) >= 0)) {
            violations.add("password must contain at least 1 special character from " + SPECIAL_CHARACTERS);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            violations.add("password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }

        return violations;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
