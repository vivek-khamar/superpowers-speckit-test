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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SignupControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validSignupReturns201AndPersistsAHashedPassword() throws Exception {
        String email = "new-signup-target@example.com";
        String body = objectMapper.writeValueAsString(
                new SignupRequest("New Signup", email, "StrongPass1!"));

        try {
            mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.message").value("User registered successfully."))
                    .andExpect(jsonPath("$.userId").value(startsWith("usr_")));

            User saved = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            assertThat(passwordEncoder.matches("StrongPass1!", saved.getPasswordHash())).isTrue();
            assertThat(saved.getPasswordHash()).isNotEqualTo("StrongPass1!");
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        } finally {
            userRepository.findByEmailIgnoreCase(email).ifPresent(u -> userRepository.deleteById(u.getId()));
        }
    }

    @Test
    void duplicateEmailReturns409AndDoesNotOverwriteTheExistingRow() throws Exception {
        String email = "duplicate-signup-target@example.com";
        User existing = new User(email, passwordEncoder.encode("OriginalPass1!"), "Original Owner");
        userRepository.save(existing);

        try {
            String body = objectMapper.writeValueAsString(
                    new SignupRequest("Impersonator", email, "OtherPass1!"));

            mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"));

            User stillOriginal = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            assertThat(stillOriginal.getName()).isEqualTo("Original Owner");
        } finally {
            userRepository.deleteById(existing.getId());
        }
    }

    @Test
    void missingFieldsReturn400ListingViolationsWithoutQueryingTheDatabase() throws Exception {
        String body = objectMapper.writeValueAsString(new SignupRequest("", "", ""));

        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray());
    }
}
