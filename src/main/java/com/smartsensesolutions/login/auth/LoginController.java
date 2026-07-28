package com.smartsensesolutions.login.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class LoginController {

    private final LoginService loginService;
    private final Duration jwtExpiry;

    public LoginController(LoginService loginService,
                            @Value("${login.jwt.expiry-seconds:86400}") long expirySeconds) {
        this.loginService = loginService;
        this.jwtExpiry = Duration.ofSeconds(expirySeconds);
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResult result = loginService.login(request);

        ResponseCookie cookie = ResponseCookie.from("jwt", result.token())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(jwtExpiry)
                .path("/")
                .build();

        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(result.response());
    }
}
