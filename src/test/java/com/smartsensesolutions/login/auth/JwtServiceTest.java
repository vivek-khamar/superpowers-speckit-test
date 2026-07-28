// src/test/java/com/smartsensesolutions/login/auth/JwtServiceTest.java
package com.smartsensesolutions.login.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-only-secret-for-unit-tests-32bytes-minimum-length", 86400);

    @Test
    void generatesATokenWhoseClaimsRoundTrip() {
        String token = jwtService.generateToken(42L, List.of("USER"));

        var claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("roles", List.class)).containsExactly("USER");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void expiryIsApproximatelyTwentyFourHoursAfterIssuedAt() {
        String token = jwtService.generateToken(1L, List.of("USER"));

        var claims = jwtService.parseClaims(token);
        long deltaSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;

        assertThat(deltaSeconds).isEqualTo(86400L);
    }

    @Test
    void parseClaimsRejectsATokenSignedWithADifferentSecret() {
        JwtService otherService =
                new JwtService("a-completely-different-secret-also-32bytes-min", 86400);
        String token = otherService.generateToken(1L, List.of("USER"));

        org.junit.jupiter.api.Assertions.assertThrows(
                io.jsonwebtoken.security.SignatureException.class,
                () -> jwtService.parseClaims(token));
    }
}
