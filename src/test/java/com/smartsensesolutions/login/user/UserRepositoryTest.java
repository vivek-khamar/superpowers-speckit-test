package com.smartsensesolutions.login.user;

import com.smartsensesolutions.login.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsSeededTestUserByEmailCaseInsensitively() {
        var found = userRepository.findByEmailIgnoreCase("TESTUSER@EXAMPLE.COM");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test User");
        assertThat(found.get().getFailedAttempts()).isZero();
        assertThat(found.get().getLockedUntil()).isNull();
    }

    @Test
    void seededPasswordHashMatchesTheDocumentedTestPassword() {
        var user = userRepository.findByEmailIgnoreCase("testuser@example.com").orElseThrow();
        var encoder = new BCryptPasswordEncoder();

        assertThat(encoder.matches("TestPassword123!", user.getPasswordHash())).isTrue();
    }

    @Test
    void findByEmailIgnoreCaseReturnsEmptyForUnknownEmail() {
        assertThat(userRepository.findByEmailIgnoreCase("nobody@example.com")).isEmpty();
    }
}
