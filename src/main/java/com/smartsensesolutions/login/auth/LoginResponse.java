package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;

public record LoginResponse(String status, String message, UserSummary user) {

    public record UserSummary(String id, String email, String name) {
    }

    public static LoginResponse success(User user) {
        return new LoginResponse(
                "success",
                "Authentication successful",
                new UserSummary("usr_" + user.getId(), user.getEmail(), user.getName()));
    }
}
