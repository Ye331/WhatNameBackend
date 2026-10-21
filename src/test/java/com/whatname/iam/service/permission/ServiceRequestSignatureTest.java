package com.whatname.iam.service.permission;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ServiceRequestSignatureTest {
    private static KeyPair pair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private static String sign(KeyPair keys, String message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @Test void canonicalMessageJoinsAndUppercasesMethod() {
        String message = ServiceRequestSignature.message("c", "k", 1700000000L, "n", "post", "/a/b?x=1", "00".repeat(32));
        assertEquals("c\nk\n1700000000\nn\nPOST\n/a/b?x=1\n" + "00".repeat(32), message);
    }

    @Test void sha256HexMatchesKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ServiceRequestSignature.sha256Hex(new byte[0]));
    }

    @Test void validSignatureVerifies() {
        KeyPair keys = pair();
        String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertTrue(ServiceRequestSignature.verify(pub, sign(keys, "hello"), "hello"));
    }

    @Test void tamperedMessageRejected() {
        KeyPair keys = pair();
        String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertFalse(ServiceRequestSignature.verify(pub, sign(keys, "hello"), "hELLO"));
    }

    @Test void wrongKeyRejected() {
        String pub = Base64.getEncoder().encodeToString(pair().getPublic().getEncoded());
        assertFalse(ServiceRequestSignature.verify(pub, sign(pair(), "m"), "m"));
    }

    @Test void pemArmoredKeyAccepted() {
        KeyPair keys = pair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        assertTrue(ServiceRequestSignature.verify(pem, sign(keys, "m"), "m"));
    }

    @Test void malformedSignatureRejected() {
        KeyPair keys = pair();
        String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertFalse(ServiceRequestSignature.verify(pub, Base64.getEncoder().encodeToString(new byte[10]), "m"));
    }
}
