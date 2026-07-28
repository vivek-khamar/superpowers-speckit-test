package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;
import com.smartsensesolutions.login.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        loginService = new LoginService(userRepository, passwordEncoder, new LoginRequestValidator(), jwtService, 5, 15);
    }

    @Test
    void successfulLoginResetsFailuresAndIssuesAToken() {
        User user = User.existing(7L, "ada@example.com", "hashed-value", "Ada Lovelace", 3, null);
        when(userRepository.findByEmailIgnoreCaseForUpdate("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hashed-value")).thenReturn(true);
        when(jwtService.generateToken(7L, List.of("USER"))).thenReturn("signed.jwt.token");

        LoginResult result = loginService.login(new LoginRequest("ada@example.com", "correct-password"));

        assertThat(result.token()).isEqualTo("signed.jwt.token");
        assertThat(result.response().status()).isEqualTo("success");
        assertThat(result.response().user().id()).isEqualTo("usr_7");

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getFailedAttempts()).isZero();
        assertThat(savedCaptor.getValue().getLockedUntil()).isNull();
    }

    @Test
    void wrongPasswordIncrementsFailuresAndThrowsAuthFailed() {
        User user = User.existing(7L, "ada@example.com", "hashed-value", "Ada Lovelace", 0, null);
        when(userRepository.findByEmailIgnoreCaseForUpdate("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(new LoginRequest("ada@example.com", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class);

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void fifthConsecutiveFailureLocksTheAccount() {
        User user = User.existing(7L, "ada@example.com", "hashed-value", "Ada Lovelace", 4, null);
        when(userRepository.findByEmailIgnoreCaseForUpdate("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(new LoginRequest("ada@example.com", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class);

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getFailedAttempts()).isEqualTo(5);
        assertThat(savedCaptor.getValue().getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void lockedAccountThrowsAccountLockedWithoutCheckingPassword() {
        User user = User.existing(7L, "ada@example.com", "hashed-value", "Ada Lovelace", 5,
                Instant.now().plusSeconds(600));
        when(userRepository.findByEmailIgnoreCaseForUpdate("ada@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> loginService.login(new LoginRequest("ada@example.com", "irrelevant")))
                .isInstanceOf(AccountLockedException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void unknownEmailStillRunsARealPasswordComparisonAndThrowsAuthFailed() {
        when(userRepository.findByEmailIgnoreCaseForUpdate("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(new LoginRequest("nobody@example.com", "anything")))
                .isInstanceOf(AuthenticationFailedException.class);

        // Timing-safety: a real BCrypt comparison must run even when no user
        // exists, so this path costs the same as a wrong-password match.
        verify(passwordEncoder).matches(eq("anything"), anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void rejectsRequestFailingValidationBeforeTouchingRepository() {
        assertThatThrownBy(() -> loginService.login(new LoginRequest("", "")))
                .isInstanceOf(LoginValidationException.class);

        Mockito.verifyNoInteractions(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void dummyHashIsAValidBCryptHashARealEncoderCanCompareWithoutThrowing() {
        // Regression guard: DUMMY_HASH must remain a syntactically valid,
        // parseable BCrypt hash. If it's ever edited to something malformed
        // or a different format, a real BCryptPasswordEncoder would throw on
        // every unknown-email login attempt instead of just returning false —
        // reintroducing the timing side-channel this constant exists to close.
        PasswordEncoder realEncoder = new BCryptPasswordEncoder();

        boolean matches = realEncoder.matches("anything", LoginService.DUMMY_HASH);

        assertThat(matches).isFalse();
    }
}
