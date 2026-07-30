package com.smartsensesolutions.login.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartsensesolutions.login.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "signup.rate-limit.max-attempts=2",
        "signup.rate-limit.window-seconds=60"
})
class SignupRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exceedingTheConfiguredMaxAttemptsReturns429() throws Exception {
        String body = objectMapper.writeValueAsString(
                new SignupRequest("Rate Limited", "rate-limit-invalid-email", "short"));

        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body));
        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body));

        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }
}
