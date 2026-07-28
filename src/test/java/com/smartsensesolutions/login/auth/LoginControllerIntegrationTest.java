package com.smartsensesolutions.login.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartsensesolutions.login.AbstractIntegrationTest;
import com.smartsensesolutions.login.user.User;
import com.smartsensesolutions.login.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LoginControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validCredentialsReturn200WithCookieAndResetFailures() throws Exception {
        String body = objectMapper.writeValueAsString(
                new LoginRequest("testuser@example.com", "TestPassword123!"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.user.email").value("testuser@example.com"))
                .andExpect(cookie().exists("jwt"))
                .andExpect(cookie().httpOnly("jwt", true))
                .andExpect(cookie().secure("jwt", true))
                .andExpect(cookie().maxAge("jwt", 86400));

        User reloaded = userRepository.findByEmailIgnoreCase("testuser@example.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getFailedAttempts()).isZero();
    }

    @Test
    void wrongPasswordReturns401WithGenericBody() throws Exception {
        String email = "wrong-password-target@example.com";
        User user = new User(email, passwordEncoder.encode("RealPassword123!"), "Wrong Password Target");
        userRepository.save(user);

        try {
            String body = objectMapper.writeValueAsString(
                    new LoginRequest(email, "wrong-password"));

            mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH_FAILED"))
                    .andExpect(jsonPath("$.message").value("Invalid email or password."));
        } finally {
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    void unknownEmailReturnsIdenticalResponseToWrongPassword() throws Exception {
        String body = objectMapper.writeValueAsString(
                new LoginRequest("no-such-user@example.com", "anything"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void missingFieldsReturn400ListingViolations() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest("", ""));

        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void fifthConsecutiveWrongPasswordLocksTheAccountFor423() throws Exception {
        String email = "lockout-target@example.com";
        User user = new User(email, passwordEncoder.encode("RealPassword123!"), "Lockout Target");
        userRepository.save(user);

        try {
            String wrongBody = objectMapper.writeValueAsString(new LoginRequest(email, "wrong-password"));
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(wrongBody))
                        .andExpect(status().isUnauthorized());
            }

            String rightBody = objectMapper.writeValueAsString(new LoginRequest(email, "RealPassword123!"));
            mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content(rightBody))
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"));
        } finally {
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    void concurrentWrongPasswordAttemptsDoNotExceedTheLockoutThreshold() throws Exception {
        String email = "concurrency-target@example.com";
        User user = new User(email, passwordEncoder.encode("RealPassword123!"), "Concurrency Target");
        userRepository.save(user);

        int threadCount = 10;
        java.util.concurrent.ExecutorService executorService =
                java.util.concurrent.Executors.newFixedThreadPool(threadCount);

        try {
            String wrongBody = objectMapper.writeValueAsString(new LoginRequest(email, "wrong-password"));
            java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    try {
                        startLatch.await();
                        mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(APPLICATION_JSON)
                                .content(wrongBody));
                    } catch (Exception ignored) {
                        // Individual response status is irrelevant here; only the
                        // final persisted failedAttempts count is asserted below.
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();

            User reloaded = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(reloaded.getFailedAttempts()).isEqualTo(5);
        } finally {
            executorService.shutdown();
            executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            userRepository.deleteById(user.getId());
        }
    }
}
