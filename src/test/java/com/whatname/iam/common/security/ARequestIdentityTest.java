package com.whatname.iam.common.security;

import static org.junit.jupiter.api.Assertions.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

import java.security.*;
import java.time.*;
import java.util.Base64;

class ARequestIdentityTest {
    private static KeyPair keys;
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private static JwtTokenService tokens;
    private ARequestIdentity identity;

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        tokens = tokens(NOW);
    }

    @BeforeEach
    void create() {
        RequestContextHolder.resetRequestAttributes();
        identity = new ARequestIdentity(tokens);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void verifiedTokenDeterminesActorAndRequestActorClaimsAreIgnored() {
        var request = request("Bearer " + tokens.issue(7, 2));
        request.addParameter("actorId", "999");
        request.addHeader("X-User-Id", "999");
        request.setAttribute("actorId", 999L);
        assertEquals(7, identity.currentUserId());
    }

    @Test
    void missingRequestOrAuthorizationRefuses() {
        invalid(identity::currentUserId);
        request(null);
        invalid(identity::currentUserId);
    }

    @Test
    void malformedDuplicateAndUnsignedCredentialsRefuse() {
        for (String value :
                new String[] {"Basic abc", "Bearer malformed", "Bearer eyJhbGciOiJub25lIn0.e30."}) {
            request(value);
            invalid(identity::currentUserId);
        }
        var request = request("Bearer " + tokens.issue(7, 1));
        request.addHeader("Authorization", "Bearer " + tokens.issue(9, 1));
        invalid(identity::currentUserId);
    }

    @Test
    void forgedAndExpiredTokensRefuse() {
        String[] parts = tokens.issue(7, 1).split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        request(
                "Bearer "
                        + parts[0]
                        + "."
                        + parts[1]
                        + "."
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
        invalid(identity::currentUserId);
        request("Bearer " + tokens(NOW.minusSeconds(3601)).issue(7, 1));
        invalid(identity::currentUserId);
    }

    @Test
    void everyCallUsesTheCurrentRequestWithoutRetainingEarlierActor() {
        request("Bearer " + tokens.issue(7, 1));
        assertEquals(7, identity.currentUserId());
        request("Bearer " + tokens.issue(9, 1));
        assertEquals(9, identity.currentUserId());
        RequestContextHolder.resetRequestAttributes();
        invalid(identity::currentUserId);
    }

    @Test
    void backgroundThreadCannotBorrowTheCallingRequest() throws Exception {
        request("Bearer " + tokens.issue(7, 1));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertEquals(
                    ErrorCode.INVALID_TOKEN,
                    executor.submit(
                                    () ->
                                            assertThrows(
                                                            BusinessException.class,
                                                            identity::currentUserId)
                                                    .errorCode())
                            .get());
        }
    }

    @Test
    void missingVerifierIsAnOperationalFailureRatherThanIdentityAcceptance() {
        request("Bearer " + tokens.issue(7, 1));
        var unavailable = new ARequestIdentity(new JwtTokenService("", "", "", "", ""));
        assertEquals(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                assertThrows(BusinessException.class, unavailable::currentUserId).errorCode());
    }

    private static MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest();
        if (authorization != null) request.addHeader("Authorization", authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static JwtTokenService tokens(Instant instant) {
        return new JwtTokenService(
                Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                "a-identity-test",
                "iam",
                "test-key",
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static void invalid(Runnable call) {
        assertEquals(
                ErrorCode.INVALID_TOKEN,
                assertThrows(BusinessException.class, call::run).errorCode());
    }
}
