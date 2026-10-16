package com.whatname.iam.service.auth;

import static com.whatname.iam.service.auth.PasswordHasher.VerificationResult.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;

class PasswordHasherTest {
    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final String PASSWORD = "MapleRiver7!";
    private static String stored;

    @BeforeAll
    static void createRealScryptFixture() {
        stored = HASHER.hash(PASSWORD);
    }

    @Test
    void verifiesCorrectPasswordAndRejectsWrongPassword() {
        assertEquals(MATCH, HASHER.verify(PASSWORD, stored));
        assertEquals(MISMATCH, HASHER.verify("MapleRiver8!", stored));
        assertFalse(HASHER.matches("MapleRiver8!", stored));
    }

    @Test
    void generatesFreshSaltAndBothEncodingsVerify() {
        String second = HASHER.hash(PASSWORD);
        assertNotEquals(stored, second);
        assertNotEquals(stored.split("\\$")[2], second.split("\\$")[2]);
        assertTrue(HASHER.matches(PASSWORD, stored));
        assertTrue(HASHER.matches(PASSWORD, second));
    }

    @Test
    void formatContainsBoundedParametersSaltAndKeyAndFitsBothVarchar255Fields() {
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("110801", parts[1]);
        assertEquals(16, Base64.getDecoder().decode(parts[2]).length);
        assertEquals(32, Base64.getDecoder().decode(parts[3]).length);
        assertEquals(77, stored.length());
        assertTrue(stored.chars().allMatch(character -> character < 128));
        assertTrue(stored.length() <= 255);
        assertFalse(stored.contains(PASSWORD));
    }

    @Test
    void interoperatesWithRealSpringEncoderAtTheDocumentedProfile() {
        var library = new SCryptPasswordEncoder(1 << 17, 8, 1, 32, 16);
        assertTrue(library.matches(PASSWORD, stored));
        assertTrue(HASHER.matches(PASSWORD, library.encode(PASSWORD)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingPasswordWithoutThrowingDuringVerification(String password) {
        assertEquals(INVALID_PASSWORD, HASHER.verify(password, stored));
        assertFalse(HASHER.matches(password, stored));
        assertThrows(IllegalArgumentException.class, () -> HASHER.hash(password));
    }

    @Test
    void rejectsOversizedPasswordAndDoesNotEchoItInException() {
        String oversized = "a".repeat(65);
        assertEquals(INVALID_PASSWORD, HASHER.verify(oversized, stored));
        var exception = assertThrows(IllegalArgumentException.class, () -> HASHER.hash(oversized));
        assertFalse(exception.getMessage().contains(oversized));
    }

    @ParameterizedTest
    @MethodSource("brokenEncodings")
    void malformedOrUnsupportedStorageNeverPasses(String encoded) {
        assertEquals(INVALID_HASH, assertDoesNotThrow(() -> HASHER.verify(PASSWORD, encoded)));
        assertFalse(HASHER.matches(PASSWORD, encoded));
    }

    static Stream<String> brokenEncodings() {
        String[] parts = stored.split("\\$");
        String nonCanonicalSalt = parts[2].substring(0, 21) + "B==";
        return Stream.of(null, "", "not-a-hash", PASSWORD, "{bcrypt}value", stored + "$extra",
                stored.substring(0, stored.length() - 1), stored.replace("110801", "100801"),
                stored.replace("110801", "7fff0801"), stored.replace("110801", "1108ff"),
                "$110801$bad$bad", "$110801$" + nonCanonicalSalt + "$" + parts[3],
                stored.replaceFirst("\\$110801\\$", "\\$110801\\$!"));
    }

    @Test
    void preservesSpacesCaseAndUnicodeAtTheMaximumCodePointLength() {
        String password = " A1" + "\uD801\uDC00".repeat(61);
        assertEquals(64, password.codePointCount(0, password.length()));
        String encoded = HASHER.hash(password);
        assertTrue(HASHER.matches(password, encoded));
        assertFalse(HASHER.matches(password.toLowerCase(java.util.Locale.ROOT), encoded));
        assertFalse(HASHER.matches(password.strip(), encoded));
    }
}
