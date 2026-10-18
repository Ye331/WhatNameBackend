package com.whatname.iam.service.auth;

import static com.whatname.iam.service.auth.PasswordPolicy.ValidationResult.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class PasswordPolicyTest {
    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final PasswordPolicy POLICY = new PasswordPolicy(HASHER);
    private static final PasswordPolicy.IdentityContext IDENTITY =
            new PasswordPolicy.IdentityContext("employee-demo", "zhangsan", "13800138000");
    private static final String RECENT_PASSWORD = "RecentMaple5";
    private static List<String> history;

    @BeforeAll
    static void createRealRecentHistoryNewestFirst() {
        history = List.of(HASHER.hash("OldMaple1"), HASHER.hash("OldMaple2"),
                HASHER.hash("OldMaple3"), HASHER.hash("OldMaple4"), HASHER.hash(RECENT_PASSWORD));
    }

    @ParameterizedTest
    @CsvSource({"7,INVALID_LENGTH", "8,VALID", "64,VALID", "65,INVALID_LENGTH"})
    void enforcesLengthBoundaries(int length, PasswordPolicy.ValidationResult expected) {
        assertEquals(expected, POLICY.validate("a".repeat(length - 1) + "1", IDENTITY, List.of()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingPassword(String password) {
        assertEquals(PASSWORD_REQUIRED, POLICY.validate(password, IDENTITY, List.of()));
    }

    @ParameterizedTest
    @CsvSource({"abcdefgh,LETTER_AND_DIGIT_REQUIRED", "12345678,LETTER_AND_DIGIT_REQUIRED",
            "!!!!!!!!,LETTER_AND_DIGIT_REQUIRED", "abcdefg1,VALID", "abcdef1!,VALID"})
    void requiresLettersAndDigitsButNeitherUppercaseNorSymbols(String password,
                                                              PasswordPolicy.ValidationResult expected) {
        assertEquals(expected, POLICY.validate(password, IDENTITY, List.of()));
    }

    @Test
    void countsUnicodeCodePointsAndAcceptsUnicodeLettersAndDigits() {
        String supplementaryLetter = "\uD801\uDC00";
        assertEquals(INVALID_LENGTH, POLICY.validate(supplementaryLetter.repeat(6) + "1", IDENTITY, List.of()));
        assertEquals(VALID, POLICY.validate(supplementaryLetter.repeat(63) + "1", IDENTITY, List.of()));
        assertEquals(INVALID_LENGTH, POLICY.validate(supplementaryLetter.repeat(64) + "1", IDENTITY, List.of()));
        assertEquals(VALID, POLICY.validate("密码测试abcd１２", IDENTITY, List.of()));
    }

    @Test
    void detectsAllFullIdentityFragmentsIgnoringCaseAndOuterContextWhitespace() {
        assertEquals(USERNAME_INCLUDED, POLICY.validate("XEMPLOYEE-DEMO7", IDENTITY, List.of()));
        assertEquals(NAME_PINYIN_INCLUDED, POLICY.validate("XZHANGSAN7", IDENTITY, List.of()));
        assertEquals(PHONE_INCLUDED, POLICY.validate("Ab13800138000", IDENTITY, List.of()));
        var padded = new PasswordPolicy.IdentityContext(" employee-demo ", " zhangsan ", " 13800138000 ");
        assertEquals(NAME_PINYIN_INCLUDED, POLICY.validate("Xzhangsan7", padded, List.of()));
        assertEquals(VALID, POLICY.validate("Maple13800", IDENTITY, List.of()));
        assertEquals(VALID, POLICY.validate("MapleRiver7", IDENTITY, List.of()));
    }

    @Test
    void caseFoldingDoesNotDependOnMachineLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var identity = new PasswordPolicy.IdentityContext("indigo", null, null);
            assertEquals(USERNAME_INCLUDED, POLICY.validate("XINDIGO7", identity, List.of()));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void requiresUsernameContextAndExplicitHistoryButAllowsGenuinelyAbsentOptionalFields() {
        assertEquals(IDENTITY_CONTEXT_REQUIRED, POLICY.validate("MapleRiver7", null, List.of()));
        assertEquals(IDENTITY_CONTEXT_REQUIRED, POLICY.validate("MapleRiver7",
                new PasswordPolicy.IdentityContext(null, null, null), List.of()));
        assertEquals(IDENTITY_CONTEXT_REQUIRED, POLICY.validate("MapleRiver7",
                new PasswordPolicy.IdentityContext("  ", "", ""), List.of()));
        assertEquals(VALID, POLICY.validate("MapleRiver7",
                new PasswordPolicy.IdentityContext("employee-demo", null, ""), List.of()));
        assertEquals(HISTORY_REQUIRED, POLICY.validate("MapleRiver7", IDENTITY, null));
        assertEquals("IdentityContext[redacted]", IDENTITY.toString());
    }

    @Test
    void permitsEmptyHistoryAndRejectsMatchAtNewestOrFifthEntry() {
        assertEquals(VALID, POLICY.validate("MapleRiver7", IDENTITY, List.of()));
        assertEquals(PASSWORD_REUSED, POLICY.validate("OldMaple1", IDENTITY, history));
        assertEquals(PASSWORD_REUSED, POLICY.validate(RECENT_PASSWORD, IDENTITY, history));
    }

    @Test
    void detectsReuseAcrossDifferentRandomSalts() {
        String anotherEncoding = HASHER.hash(RECENT_PASSWORD);
        assertNotEquals(history.get(4), anotherEncoding);
        assertEquals(PASSWORD_REUSED, POLICY.validate(RECENT_PASSWORD, IDENTITY,
                List.of(anotherEncoding)));
    }

    @Test
    void permitsPasswordNotFoundInFiveRealHashesAndDoesNotQueryOlderHistory() {
        assertEquals(VALID, POLICY.validate("OutsideWindow6", IDENTITY, history));
    }

    @Test
    void rejectsMoreThanFiveHashesInsteadOfSilentlyAcceptingWrongHistoryInput() {
        var oversized = new ArrayList<>(history);
        oversized.add(HASHER.hash("OutsideWindow6"));
        assertEquals(INVALID_HISTORY, POLICY.validate("OutsideWindow6", IDENTITY, oversized));
    }

    @Test
    void damagedOrMissingHistoryEntryCannotBeTreatedAsAnOrdinaryMismatch() {
        assertEquals(INVALID_HISTORY, POLICY.validate("MapleRiver7", IDENTITY, List.of("broken")));
        assertEquals(INVALID_HISTORY, POLICY.validate("MapleRiver7", IDENTITY, Arrays.asList((String) null)));
        assertEquals(INVALID_HISTORY, POLICY.validate("MapleRiver7", IDENTITY,
                List.of(history.get(0), history.get(1).replace("110801", "100801"))));
    }
}
