// src/test/java/com/smartsensesolutions/login/auth/JwtServiceTest.java
package com.smartsensesolutions.login.auth;

import org.junit.jupiter.api.Test;

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

    @Test
    void tokenIsSignedWithHs256RegardlessOfConfiguredSecretLength() {
        // jjwt's Keys.hmacShaKeyFor() auto-selects HS256/HS384/HS512 based on
        // the secret's byte length, and a bare .signWith(key) follows that
        // auto-negotiated algorithm rather than pinning HS256 -- this test
        // exists specifically because that was found to actually happen
        // (HS384) with this test class's own 54-byte secret before
        // JwtService explicitly pinned Jwts.SIG.HS256. Decoding the raw JWT
        // header (unencrypted, just base64url) rather than going through
        // parseClaims(), since that method only returns the payload.
        String token = jwtService.generateToken(1L, List.of("USER"));

        String headerJson = new String(
                java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(headerJson).contains("\"alg\":\"HS256\"");
    }
}
