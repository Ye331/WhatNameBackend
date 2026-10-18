package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A internal format. Keys are deliberately absent by default; no plaintext fallback. */
@Component
public final class SensitiveDataCodec {
    private static final String PREFIX = "gcm1:";
    private static final byte[] AAD = "whatname:gcm1".getBytes(StandardCharsets.UTF_8);
    private final String encryptionKey;
    private final String phoneKey;
    private final SecureRandom random = new SecureRandom();

    public SensitiveDataCodec(@Value("${iam.security.data-key:}") String encryptionKey,
                              @Value("${iam.security.phone-hmac-key:}") String phoneKey) {
        this.encryptionKey = encryptionKey;
        this.phoneKey = phoneKey;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            if (ciphertext == null || !ciphertext.startsWith(PREFIX)) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (bytes.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey(), "AES"), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
    }

    /** Input must already be canonical; reject callers accidentally hashing another representation. */
    public String phoneHash(String normalizedPhone) {
        if (normalizedPhone == null || !normalizedPhone.matches("1[3-9][0-9]{9}"))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        byte[] key = requiredKey(phoneKey);
        if (MessageDigest.isEqual(key, requiredKey(encryptionKey)))
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        return hmac(key, normalizedPhone);
    }

    /** Mainland mobile baseline: trim, optional +86; no lossy removal of arbitrary punctuation. */
    public static String normalizePhone(String phone) {
        if (phone == null) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        String value = phone.strip();
        if (value.startsWith("+86")) value = value.substring(3);
        if (!value.matches("1[3-9][0-9]{9}")) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        return value;
    }

    static byte[] requiredKey(String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
    }

    static String hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
    }

    private byte[] dataKey() { return requiredKey(encryptionKey); }
}
