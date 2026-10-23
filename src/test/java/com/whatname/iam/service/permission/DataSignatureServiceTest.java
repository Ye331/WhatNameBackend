package com.whatname.iam.service.permission;

import static com.whatname.iam.service.permission.SignedDataType.USER_GRANTS;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.DataSigningKeys;
import com.whatname.iam.repository.permission.SignatureRepository;

import org.junit.jupiter.api.*;

import java.security.*;
import java.util.*;

class DataSignatureServiceTest {
    SignatureRepository repository;
    DataSigningKeys keys;
    DataSignatureService service;
    Map<String, Object> stored;

    @BeforeEach
    void fixture() throws Exception {
        repository = mock(SignatureRepository.class);
        keys = new DataSigningKeys();
        rotate("first");
        service = new DataSignatureService(repository, keys);
        when(repository.record(USER_GRANTS, 7)).thenReturn(Map.of("id", 7L, "grant_revision", 1L));
        when(repository.userRoles(7)).thenReturn(List.of());
        when(repository.sources(7)).thenReturn(List.of());
        doAnswer(
                        call -> {
                            stored =
                                    Map.of(
                                            "signature",
                                            call.getArgument(2),
                                            "sign_version",
                                            1,
                                            "key_id",
                                            call.getArgument(3));
                            return null;
                        })
                .when(repository)
                .save(anyString(), anyLong(), anyString(), anyString());
        when(repository.signature("USER_GRANTS", 7)).thenAnswer(call -> stored);
    }

    @Test
    void canonicalBytesSeparateNullEmptyAndDelimiterContainingValues() {
        var first = new LinkedHashMap<String, Object>();
        first.put("b", List.of());
        first.put("a", null);
        first.put("n", 7L);
        assertEquals("{\"a\":null,\"b\":[],\"n\":\"7\"}", CanonicalEvidence.json(first));
        assertNotEquals(
                CanonicalEvidence.digest(Map.of("value", "a|b")),
                CanonicalEvidence.digest(Map.of("value", List.of("a", "b"))));
        assertNotEquals(
                CanonicalEvidence.digest(first),
                CanonicalEvidence.digest(Map.of("b", List.of(), "n", 7)));
        assertEquals(
                CanonicalEvidence.digest(Map.of("b", 2, "a", 1L)),
                CanonicalEvidence.digest(Map.of("a", 1, "b", 2L)));
    }

    @Test
    void emptyGrantsHaveARealSignatureAndIllegalInsertionOrDeletionCannotPass() {
        service.sign(USER_GRANTS, 7);
        assertNotNull(service.verify(USER_GRANTS, 7));
        when(repository.userRoles(7)).thenReturn(List.of(42L));
        assertThrows(BusinessException.class, () -> service.verify(USER_GRANTS, 7));
        service.sign(USER_GRANTS, 7);
        assertNotNull(service.verify(USER_GRANTS, 7));
        when(repository.userRoles(7)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.verify(USER_GRANTS, 7));
    }

    @Test
    void rotationUsesKeyIdsAndRetainsOldVerificationWithoutAStoredPrivateKey() throws Exception {
        var evidence = Map.<String, Object>of("after", Map.of("status", 1));
        var oldSeal = service.seal(evidence);
        service.sign(USER_GRANTS, 7);
        var oldSignature = stored;
        rotate("second");
        assertTrue(service.verifySeal(evidence, oldSeal));
        assertNotNull(service.verify(USER_GRANTS, 7));
        service.sign(USER_GRANTS, 7);
        assertEquals("second", stored.get("key_id"));
        assertNotEquals(oldSignature.get("signature"), stored.get("signature"));
        keys.setPrivateKey("");
        assertTrue(service.verifySeal(evidence, oldSeal));
        assertNotNull(service.verify(USER_GRANTS, 7));
        keys.getPublicKeys().remove("first");
        assertFalse(service.verifySeal(evidence, oldSeal));
    }

    @Test
    void evidenceSealDetectsModifiedFactsAndSignaturesAreNeverRepairedOnRead() {
        var evidence = Map.<String, Object>of("decisionId", "d1", "allowed", true);
        var seal = service.seal(evidence);
        assertTrue(service.verifySeal(evidence, seal));
        assertFalse(service.verifySeal(Map.of("decisionId", "d1", "allowed", false), seal));
        assertThrows(BusinessException.class, () -> service.verify(USER_GRANTS, 7));
        verify(repository, never()).save(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void wrongPrivatePublicPairCannotCreateAReadableSignature() throws Exception {
        var unrelated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keys.setPrivateKey(Base64.getEncoder().encodeToString(unrelated.getPrivate().getEncoded()));
        assertThrows(BusinessException.class, () -> service.sign(USER_GRANTS, 7));
        verify(repository, never()).save(anyString(), anyLong(), anyString(), anyString());
    }

    private void rotate(String name) throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keys.setKeyId(name);
        keys.setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        keys.getPublicKeys()
                .put(name, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }
}
