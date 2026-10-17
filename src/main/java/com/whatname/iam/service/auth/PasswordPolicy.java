package com.whatname.iam.service.auth;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 首次设密/改密/重置复用的纯规则检查；不查询或保存历史，不负责账号状态及事务。 */
@Service
public final class PasswordPolicy {
    private final PasswordHasher hasher;

    public PasswordPolicy(PasswordHasher hasher) {
        this.hasher = Objects.requireNonNull(hasher);
    }

    public enum ValidationResult {
        VALID,
        PASSWORD_REQUIRED,
        INVALID_LENGTH,
        LETTER_AND_DIGIT_REQUIRED,
        IDENTITY_CONTEXT_REQUIRED,
        USERNAME_INCLUDED,
        NAME_PINYIN_INCLUDED,
        PHONE_INCLUDED,
        HISTORY_REQUIRED,
        INVALID_HISTORY,
        PASSWORD_REUSED,
        HISTORY_VERIFICATION_FAILED
    }

    /**
     * Username is required. Name pinyin and phone may be absent only when genuinely unavailable.
     * Caller supplies the full name pinyin and normalized full phone, not ciphertext or HMAC.
     * Known fields must not be omitted to bypass checks. This class does not decrypt/transliterate.
     */
    public record IdentityContext(String username, String namePinyin, String phone) {
        @Override
        public String toString() {
            return "IdentityContext[redacted]";
        }
    }

    /**
     * Returns the first failure. Length counts Unicode code points; letters/digits use Java's
     * Unicode classifications. Password whitespace/case is preserved. Identity substring checks
     * ignore case (Locale.ROOT); identity fields have surrounding whitespace stripped.
     * recentHashes must be non-null, newest first, and contain 0..5 actual stored encodings.
     * Empty means no prior passwords, null means missing history, and more than five is rejected.
     * Caller owns history completeness/order; this method cannot verify database facts.
     */
    public ValidationResult validate(String password, IdentityContext identity,
                                     List<String> recentHashes) {
        if (password == null || password.isEmpty()) {
            return ValidationResult.PASSWORD_REQUIRED;
        }
        if (password.length() > PasswordHasher.MAX_PASSWORD_CODE_POINTS * 2) {
            return ValidationResult.INVALID_LENGTH;
        }
        int length = password.codePointCount(0, password.length());
        if (length < 8 || length > PasswordHasher.MAX_PASSWORD_CODE_POINTS) {
            return ValidationResult.INVALID_LENGTH;
        }
        if (!password.codePoints().anyMatch(Character::isLetter)
                || !password.codePoints().anyMatch(Character::isDigit)) {
            return ValidationResult.LETTER_AND_DIGIT_REQUIRED;
        }
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            return ValidationResult.IDENTITY_CONTEXT_REQUIRED;
        }
        String folded = password.toLowerCase(Locale.ROOT);
        if (containsIdentity(folded, identity.username())) {
            return ValidationResult.USERNAME_INCLUDED;
        }
        if (containsIdentity(folded, identity.namePinyin())) {
            return ValidationResult.NAME_PINYIN_INCLUDED;
        }
        if (containsIdentity(folded, identity.phone())) {
            return ValidationResult.PHONE_INCLUDED;
        }
        if (recentHashes == null) {
            return ValidationResult.HISTORY_REQUIRED;
        }
        if (recentHashes.size() > 5) {
            return ValidationResult.INVALID_HISTORY;
        }
        for (String oldHash : recentHashes) {
            switch (hasher.verify(password, oldHash)) {
                case MATCH:
                    return ValidationResult.PASSWORD_REUSED;
                case MISMATCH:
                    break;
                case INVALID_HASH:
                    return ValidationResult.INVALID_HISTORY;
                default:
                    return ValidationResult.HISTORY_VERIFICATION_FAILED;
            }
        }
        return ValidationResult.VALID;
    }

    private static boolean containsIdentity(String foldedPassword, String identityValue) {
        return identityValue != null && !identityValue.isBlank()
                && foldedPassword.contains(identityValue.strip().toLowerCase(Locale.ROOT));
    }
}
