package com.smartsensesolutions.login.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SignupController {

    private final SignupService signupService;
    private final SignupRateLimiter rateLimiter;

    public SignupController(SignupService signupService, SignupRateLimiter rateLimiter) {
        this.signupService = signupService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/v1/auth/signup")
    public ResponseEntity<SignupResponse> signup(@RequestBody SignupRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire(httpRequest.getRemoteAddr())) {
            throw new RateLimitExceededException();
        }

        SignupResponse response = signupService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
