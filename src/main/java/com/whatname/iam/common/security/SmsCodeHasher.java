package com.whatname.iam.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

@Component
public class SmsCodeHasher {
    private final String key;

    public SmsCodeHasher(@Value("${iam.security.sms-hmac-key:}") String key) {
        this.key = key;
    }

    public String hash(String phoneLookup, String purpose, String code) {
        return SmsCodeHasher.hmac(
                SmsCodeHasher.requiredKey(key),
                "sms1\n" + phoneLookup + "\n" + purpose + "\n" + code);
    }

    public boolean matches(String phoneLookup, String purpose, String code, String expected) {
        return MessageDigest.isEqual(
                hash(phoneLookup, purpose, code).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
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

}
