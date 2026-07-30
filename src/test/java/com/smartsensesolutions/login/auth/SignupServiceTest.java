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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SignupService signupService;

    @BeforeEach
    void setUp() {
        signupService = new SignupService(userRepository, passwordEncoder, new SignupRequestValidator());
    }

    @Test
    void validSignupHashesPasswordAndPersistsUser() {
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            return User.existing(7L, saved.getEmail(), saved.getPasswordHash(), saved.getName(), 0, null);
        });

        SignupResponse response = signupService.signup(
                new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!"));

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.userId()).isEqualTo("usr_7");

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getPasswordHash()).isEqualTo("hashed-value");
    }

    @Test
    void rejectsRequestFailingValidationBeforeTouchingRepository() {
        assertThatThrownBy(() -> signupService.signup(new SignupRequest("", "", "")))
                .isInstanceOf(SignupValidationException.class);

        Mockito.verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void rejectsDuplicateEmailFoundByThePreCheckWithoutSaving() {
        User existing = User.existing(3L, "ada@example.com", "hash", "Ada", 0, null);
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> signupService.signup(
                new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void mapsADatabaseUniqueConstraintViolationTo409NotA500() {
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> signupService.signup(
                new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }
}
