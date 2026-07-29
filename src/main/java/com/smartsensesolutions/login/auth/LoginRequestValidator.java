package com.smartsensesolutions.login.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class LoginRequestValidator {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public List<String> validate(LoginRequest request) {
        List<String> violations = new ArrayList<>();

        if (isBlank(request.email())) {
            violations.add("email is required");
        } else if (!EMAIL_PATTERN.matcher(request.email()).matches()) {
            violations.add("email must be a valid email address");
        }

        if (isBlank(request.password())) {
            violations.add("password is required");
        }

        return violations;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
