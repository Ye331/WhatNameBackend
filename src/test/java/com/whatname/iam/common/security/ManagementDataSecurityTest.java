package com.whatname.iam.common.security;

import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.repository.audit.AuditRepository;
import com.whatname.iam.repository.permission.SignatureRepository;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.*;

import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.security.KeyPairGenerator;
import java.util.*;

/** 使用真实的规范化序列化、Ed25519 签名和审计封印；Repository 存储和事务是内存替身， 这里不验证数据库回滚、加锁或权限。 */
class ManagementDataSecurityTest {
    SignatureRepository repository;
    DataSignatureService signatures;
    ManagementDataSecurity data;
    UserAccount user;
    Map<String, Object> row;
    Map<String, Map<String, Object>> stored;
    Map<String, Object> log;
    final TransactionTemplate tx = new TransactionTemplate(new LifecycleOnlyTransactionManager());

    @BeforeEach
    void fixture() throws Exception {
        user = new UserAccount();
        user.id = 7L;
        user.username = "fixture";
        user.realName = "Demo Person";
        user.phone = "13800000007";
        user.passwordHash = "hash";
        user.deptId = 2L;
        user.status = 1;
        user.source = 2;
        user.passwordInitialized = 1;
        user.sessionVersion = 1L;
        user.grantRevision = 1L;
        user.hrVersion = 0L;
        row = new LinkedHashMap<>();
        Object[] values = {
            user.id,
            user.username,
            user.realName,
            user.phone,
            user.passwordHash,
            null,
            user.deptId,
            user.status,
            user.source,
            null,
            user.passwordInitialized,
            user.sessionVersion,
            user.grantRevision,
            user.hrVersion,
            null,
            null
        };
        String[] columns = USER_IDENTITY.fields.split(",");
        for (int i = 0; i < columns.length; i++) row.put(columns[i], values[i]);
        repository = mock(SignatureRepository.class);
        stored = new HashMap<>();
        when(repository.record(USER_IDENTITY, 7)).thenAnswer(call -> row);
        when(repository.record(USER_GRANTS, 7))
                .thenAnswer(call -> Map.of("id", 7L, "grant_revision", row.get("grant_revision")));
        when(repository.userRoles(7)).thenReturn(List.of());
        when(repository.sources(7)).thenReturn(List.of());
        doAnswer(
                        call -> {
                            stored.put(
                                    call.getArgument(0),
                                    Map.of(
                                            "signature",
                                            call.getArgument(2),
                                            "sign_version",
                                            1,
                                            "key_id",
                                            call.getArgument(3)));
                            return null;
                        })
                .when(repository)
                .save(anyString(), eq(7L), anyString(), anyString());
        when(repository.signature(anyString(), eq(7L)))
                .thenAnswer(call -> stored.get(call.getArgument(0)));
        var keys = new DataSigningKeys();
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keys.setKeyId("unit-fixture");
        keys.setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        keys.getPublicKeys()
                .put(
                        "unit-fixture",
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        signatures = new DataSignatureService(repository, keys);
        var auditMapper = mock(AuditRepository.class);
        doAnswer(
                        call -> {
                            log = new LinkedHashMap<>(call.getArgument(0));
                            return null;
                        })
                .when(auditMapper)
                .insert(anyMap());
        data =
                new ManagementDataSecurity(
                        signatures,
                        mock(PermissionService.class),
                        new AuditService(auditMapper, signatures));
    }

    @Test
    void newInsertProvenanceCreatesBothRealSignaturesIncludingEmptyGrants() {
        tx.executeWithoutResult(
                status -> {
                    AccountCreation.inserted(user);
                    data.signIdentity(user);
                    signatures.verify(USER_IDENTITY, 7);
                    signatures.verify(USER_GRANTS, 7);
                    var facts = (Map<?, ?>) signatures.snapshot(USER_GRANTS, 7).get("facts");
                    assertEquals(List.of(), facts.get("roles"));
                    assertEquals(List.of(), facts.get("sources"));
                });
    }

    @Test
    void oldMissingSignatureCannotBootstrapByNullingCreationTimestamp() {
        user.createdAt = null;
        expect(() -> tx.executeWithoutResult(status -> data.signIdentity(user)));
        assertEquals(Map.of(), stored);
    }

    @Test
    void creationProvenanceDoesNotAcceptADifferentObjectWithSameId() {
        var another = new UserAccount();
        another.id = 7L;
        expect(
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    AccountCreation.inserted(another);
                                    data.signIdentity(user);
                                }));
        assertEquals(Map.of(), stored);
    }

    @Test
    void tamperedCompleteFactIsRejectedWithoutReplacingSignature() {
        signatures.sign(USER_IDENTITY, 7);
        var before = new HashMap<>(stored);
        row.put("temp_reason", "tampered");
        user.tempReason = "tampered";
        expect(() -> tx.executeWithoutResult(status -> data.verifyIdentity(user)));
        assertEquals(before, stored);
    }

    @Test
    void authenticDifferentSnapshotCannotValidateTheAObject() {
        row.put("session_version", 2L);
        signatures.sign(USER_IDENTITY, 7);
        expect(() -> tx.executeWithoutResult(status -> data.verifyIdentity(user)));
    }

    @Test
    void verifiedMutationSignsTheNewHashAndSessionWithoutRepairingMissingGrants() {
        signatures.sign(USER_IDENTITY, 7);
        tx.executeWithoutResult(
                status -> {
                    data.verifyIdentity(user);
                    row.put("password_hash", "new-hash");
                    user.passwordHash = "new-hash";
                    row.put("session_version", 2L);
                    user.sessionVersion = 2L;
                    data.signIdentity(user);
                    signatures.verify(USER_IDENTITY, 7);
                });
        assertFalse(stored.containsKey("USER_GRANTS"));
    }

    @Test
    void verificationAndInsertProvenanceDisappearAfterTransactionCompletion() {
        signatures.sign(USER_IDENTITY, 7);
        tx.executeWithoutResult(
                status -> {
                    data.verifyIdentity(user);
                    AccountCreation.inserted(user);
                });
        assertFalse(AccountCreation.contains(user));
        expect(() -> tx.executeWithoutResult(status -> data.signIdentity(user)));
    }

    @Test
    void departmentProjectionUsesFullDIdentityAndAcceptsStringIds() {
        signatures.sign(USER_IDENTITY, 7);
        tx.executeWithoutResult(
                status ->
                        data.verifyUsers(
                                List.of(Map.of("userId", "7", "deptId", "2", "status", 1))));
        row.put("password_hash", "tampered-unprojected-field");
        expect(
                () ->
                        tx.executeWithoutResult(
                                status ->
                                        data.verifyUsers(
                                                List.of(
                                                        Map.of(
                                                                "userId", "7", "deptId", "2",
                                                                "status", 1)))));
    }

    @Test
    void auditPreservesReasonAndVerifiableSealWithoutSensitiveEntityFields() throws Exception {
        signatures.sign(USER_IDENTITY, 7);
        tx.executeWithoutResult(
                status -> {
                    data.verifyIdentity(user);
                    data.audit("US04_STATUS", 9L, 7L, "approved operation reason");
                });
        @SuppressWarnings("unchecked")
        var evidence = new ObjectMapper().readValue((String) log.get("evidence"), Map.class);
        assertEquals("approved operation reason", evidence.get("reason"));
        @SuppressWarnings("unchecked")
        var seal = (Map<String, Object>) evidence.remove("seal");
        assertTrue(signatures.verifySeal(evidence, seal));
        assertFalse(evidence.toString().contains(user.passwordHash));
        assertFalse(evidence.toString().contains(user.phone));
    }

    @Test
    void constructedHrContextHasNoSigningAuthorityWithoutEventExecution() {
        var actor =
                new com.whatname.iam.service.event.TrustedEventContext(
                        "HR", "any-client", "request");
        assertEquals(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        tx.executeWithoutResult(
                                                status ->
                                                        data.signOrganization(
                                                                "HR_EMPLOYEE",
                                                                actor,
                                                                Map.of(
                                                                        "eventId", "event",
                                                                        "userId", "7"))))
                        .errorCode());
        assertTrue(stored.isEmpty());
    }

    private void expect(org.junit.jupiter.api.function.Executable work) {
        assertEquals(
                ErrorCode.INTEGRITY_FAILURE,
                assertThrows(BusinessException.class, work).errorCode());
    }

    private static final class LifecycleOnlyTransactionManager
            extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction() {
            return new Object();
        }

        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        protected void doCommit(DefaultTransactionStatus status) {}

        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
