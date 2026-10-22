package com.whatname.iam.service.department;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.mapper.service.ServiceIdentityMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.event.TrustedEventContext;
import com.whatname.iam.service.permission.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

class DepartmentAccessServiceTest {
    private final AccountManagementPolicy policy = mock(AccountManagementPolicy.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final AuthService auth = mock(AuthService.class);
    private final ManagementDataSecurity data = mock(ManagementDataSecurity.class);
    private final AdminOrganizationLifecycle lifecycle = mock(AdminOrganizationLifecycle.class);
    private final ServiceRequestAuthenticator services = mock(ServiceRequestAuthenticator.class);
    private final ServiceIdentityMapper identities = mock(ServiceIdentityMapper.class);
    private final DepartmentAccessService access =
            new DepartmentAccessService(policy, jwt, auth, data, lifecycle, services, identities);
    private static final TrustedEventContext HR =
            new TrustedEventContext("HR", "hr-client", "nonce-1");

    private static JwtTokenService.SessionPrincipal session() {
        return new JwtTokenService.SessionPrincipal(
                7, 1, "jti", Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
    }

    @Test
    void managerPreparationRechecksSessionAfterPolicyLocks() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test");
        var session = session();
        when(jwt.verifyBearer("Bearer test")).thenReturn(session);
        var actor = access.authenticateManager(request, "IAM_DEPARTMENTS_WRITE");
        assertEquals("7", actor.actorId());
        access.prepareManagement(actor);
        var order = inOrder(policy, auth);
        order.verify(policy).prepare(7);
        order.verify(auth).requireCurrentSession(session);
        access.prepareDepartmentManagement(actor, 9);
        order.verify(policy).prepareDepartment(7, 9);
        order.verify(auth).requireCurrentSession(session);
    }

    @Test
    void scopeStillRequiresFunctionPermission() {
        var actor = new DepartmentAccessService.Manager("7", session());
        when(policy.scope(7)).thenReturn(Set.of(9L));
        assertEquals(Set.of(9L), access.scope(actor, "IAM_DEPARTMENTS_VIEW", List.of()));
        doThrow(new BusinessException(ErrorCode.PERMISSION_DENIED))
                .when(data)
                .requireFunction(7, "IAM_DEPARTMENTS_WRITE");
        failure(
                ErrorCode.PERMISSION_DENIED,
                () -> access.scope(actor, "IAM_DEPARTMENTS_WRITE", List.of()));
    }

    @Test
    void missingOrMismatchedSessionCannotPrepareChanges() {
        for (var actor :
                List.of(
                        new DepartmentAccessService.Manager("7", null),
                        new DepartmentAccessService.Manager("8", session()))) {
            failure(ErrorCode.INVALID_TOKEN, () -> access.prepareManagement(actor));
            failure(ErrorCode.INVALID_TOKEN, () -> access.prepareDepartmentManagement(actor, 9));
        }
        verifyNoInteractions(policy, auth);
    }

    @Test
    void hrAuthenticationUsesVerifiedClientAndNonce() {
        var request = new MockHttpServletRequest();
        request.addHeader(ServiceRequestSignature.NONCE_HEADER, "nonce-1");
        when(services.authenticate(request, "HR_SYNC"))
                .thenReturn(Map.of("client_id", "hr-client"));
        assertEquals(HR, access.authenticateHr(request, "HR_TRANSFER"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidContexts")
    void incompleteOrWrongSourceContextsAreRejected(
            String description, TrustedEventContext context, String event) {
        failure(ErrorCode.INVALID_SERVICE, () -> access.verifyTrustedEvent(context, event));
        verifyNoInteractions(identities);
    }

    static Stream<Arguments> invalidContexts() {
        return Stream.of(
                Arguments.of("缺少上下文", null, "HR_TRANSFER"),
                Arguments.of("缺少事件类型", HR, null),
                Arguments.of(
                        "非 HR 来源",
                        new TrustedEventContext("OA", "hr-client", "nonce"),
                        "HR_TRANSFER"),
                Arguments.of("缺少客户端", new TrustedEventContext("HR", null, "nonce"), "HR_TRANSFER"),
                Arguments.of(
                        "缺少请求标识", new TrustedEventContext("HR", "hr-client", null), "HR_TRANSFER"),
                Arguments.of(
                        "请求标识空白", new TrustedEventContext("HR", "hr-client", " "), "HR_TRANSFER"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidIdentities")
    void currentServiceIdentityMustRemainEnabledAndHrScoped(
            String description, Map<String, Object> identity) {
        when(identities.find("hr-client")).thenReturn(identity);
        failure(ErrorCode.INVALID_SERVICE, () -> access.verifyTrustedEvent(HR, "HR_TRANSFER"));
    }

    static Stream<Arguments> invalidIdentities() {
        return Stream.of(
                Arguments.of("身份已删除", null),
                Arguments.of("缺少状态", Map.of("allowed_scope", "HR_SYNC")),
                Arguments.of("状态类型无效", Map.of("status", "1", "allowed_scope", "HR_SYNC")),
                Arguments.of("身份停用", Map.of("status", 0, "allowed_scope", "HR_SYNC")),
                Arguments.of("作用域不符", Map.of("status", 1, "allowed_scope", "OA_CALLBACK")),
                Arguments.of(
                        "不应绑定业务应用", Map.of("status", 1, "allowed_scope", "HR_SYNC", "app_id", 1)));
    }

    @ParameterizedTest
    @MethodSource("allowedOperations")
    void allowedOperationRepresentationsPermitTheListedEvent(Object operations) {
        var identity = identity(operations);
        when(identities.find("hr-client")).thenReturn(identity);
        assertDoesNotThrow(() -> access.verifyTrustedEvent(HR, "HR_TRANSFER"));
    }

    static Stream<Arguments> allowedOperations() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(""),
                Arguments.of("  "),
                Arguments.of("[\"HR_TRANSFER\",\"HR_MERGE\"]"),
                Arguments.of(List.of()),
                Arguments.of(List.of("HR_TRANSFER", "HR_MERGE")));
    }

    @ParameterizedTest
    @MethodSource("deniedOperations")
    void restrictedOperationCannotBeUsedForAnotherEvent(Object operations) {
        when(identities.find("hr-client")).thenReturn(identity(operations));
        failure(ErrorCode.INVALID_SERVICE, () -> access.verifyTrustedEvent(HR, "HR_TRANSFER"));
    }

    static Stream<Arguments> deniedOperations() {
        return Stream.of(Arguments.of("[\"HR_MERGE\"]"), Arguments.of(List.of("HR_MERGE")));
    }

    private Map<String, Object> identity(Object operations) {
        var identity = new HashMap<String, Object>();
        identity.put("status", 1);
        identity.put("allowed_scope", "HR_SYNC");
        identity.put("allowed_operations", operations);
        return identity;
    }

    private static void failure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).errorCode());
    }
}
