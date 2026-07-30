package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;

public record SignupResponse(String status, String message, String userId) {

    public static SignupResponse success(User user) {
        return new SignupResponse("success", "User registered successfully.", "usr_" + user.getId());
    }
}
