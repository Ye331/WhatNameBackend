package com.whatname.iam.service.user;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.common.util.IdUtils;
import com.whatname.iam.dto.common.PageResponse;
import com.whatname.iam.dto.user.UserRequests.*;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.auth.NamePinyin;
import com.whatname.iam.service.auth.PasswordHasher;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class UserService {
    private final UserAccountMapper users;
    private final AuthService auth;
    private final JwtTokenService jwt;
    private final SensitiveDataCodec codec;
    private final AccountSecurity security;
    private final PasswordHasher hasher;
    private static final int SEARCH_BATCH_SIZE = 200;
    private final TransactionTemplate tx;

    public UserService(
            UserAccountMapper users,
            AuthService auth,
            JwtTokenService jwt,
            SensitiveDataCodec codec,
            AccountSecurity security,
            PasswordHasher hasher,
            PlatformTransactionManager manager) {
        this.users = users;
        this.auth = auth;
        this.jwt = jwt;
        this.codec = codec;
        this.security = security;
        this.hasher = hasher;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(30);
    }

    public record UserView(
            String userId,
            String username,
            String sgNo,
            String realName,
            String phoneMasked,
            String deptId,
            int status,
            boolean passwordInitialized,
            int source) {}

    public record Created(
            String userId,
            String username,
            int source,
            String phoneMasked,
            String deptId,
            int status) {}

    public record StatusResult(String userId, int status) {}

    /** 查询顺序：校验输入 → 当前会话和范围 → 查询分页 → 审计。 */
    public PageResponse<UserView> list(
            String bearer,
            String keyword,
            String deptId,
            Integer accountStatus,
            Integer source,
            int page,
            int pageSize) {
        validateListRequest(keyword, accountStatus, source, page, pageSize);
        Long department = deptId == null ? null : IdUtils.parse(deptId);
        String term = keyword == null || keyword.isBlank() ? null : keyword.strip();
        String phoneHash = searchPhoneHash(term);
        var principal = jwt.verifyBearer(bearer);
        return tx.execute(
                status -> {
                    requireFreshTransaction(status.isNewTransaction());
                    security.prepareManagement(principal.userId());
                    UserAccount actor = auth.requireCurrentSession(principal);
                    Set<Long> scope = scope(actor, "IAM_USERS_VIEW");
                    if (department != null) {
                        requireScope(scope, department);
                    }
                    PageResponse<UserView> result =
                            term == null
                                    ? listPage(
                                            scope,
                                            department,
                                            accountStatus,
                                            source,
                                            page,
                                            pageSize)
                                    : searchPage(
                                            scope,
                                            department,
                                            term,
                                            phoneHash,
                                            accountStatus,
                                            source,
                                            page,
                                            pageSize);
                    security.audit("US01_LIST", actor.id, null, "SCOPED_READ");
                    return result;
                });
    }

    private static void validateListRequest(
            String keyword, Integer status, Integer source, int page, int pageSize) {
        boolean invalidPage = page < 1 || page > 1_000_000 || pageSize < 1 || pageSize > 500;
        boolean invalidKeyword = keyword != null && keyword.length() > 64;
        boolean invalidStatus = status != null && (status < 1 || status > 3);
        boolean invalidSource = source != null && (source < 1 || source > 3);
        if (invalidPage || invalidKeyword || invalidStatus || invalidSource) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private String searchPhoneHash(String term) {
        if (term == null || !term.matches("(?:\\+86)?1[3-9][0-9]{9}")) {
            return null;
        }
        return codec.phoneHash(SensitiveDataCodec.normalizePhone(term));
    }

    private PageResponse<UserView> listPage(
            Set<Long> scope,
            Long department,
            Integer status,
            Integer source,
            int page,
            int pageSize) {
        long offset = ((long) page - 1) * pageSize;
        int count = users.count(scope, department, null, null, status, source);
        List<UserView> items =
                users.list(scope, department, null, null, status, source, offset, pageSize).stream()
                        .map(this::view)
                        .toList();
        return new PageResponse<>(items, page, pageSize, count);
    }

    /** 姓名已加密，必须在同一事务快照内逐批验签、解密；总数也按实际匹配计算。 */
    private PageResponse<UserView> searchPage(
            Set<Long> scope,
            Long department,
            String term,
            String phoneHash,
            Integer status,
            Integer source,
            int page,
            int pageSize) {
        String nameTerm = normalizeName(term, ErrorCode.INVALID_ARGUMENT);
        List<UserView> items = new ArrayList<>(pageSize);
        long offset = ((long) page - 1) * pageSize;
        long afterId = 0;
        int count = 0;
        while (true) {
            List<UserAccount> batch =
                    users.searchCandidates(
                            scope,
                            department,
                            term,
                            phoneHash,
                            status,
                            source,
                            afterId,
                            SEARCH_BATCH_SIZE);
            for (UserAccount user : batch) {
                security.verifyIdentity(user);
                String name = codec.decrypt(user.realNameCiphertext);
                boolean matched =
                        Boolean.TRUE.equals(user.identifierMatched)
                                || normalizeName(name, ErrorCode.INTEGRITY_FAILURE)
                                        .contains(nameTerm);
                if (matched) {
                    if (count >= offset && items.size() < pageSize) {
                        items.add(view(user, name));
                    }
                    count = Math.addExact(count, 1);
                }
                afterId = user.id;
            }
            if (batch.size() < SEARCH_BATCH_SIZE) {
                return new PageResponse<>(items, page, pageSize, count);
            }
        }
    }

    public UserView detail(String bearer, String userId) {
        long id = IdUtils.parse(userId);
        var principal = jwt.verifyBearer(bearer);
        return tx.execute(
                status -> {
                    requireFreshTransaction(status.isNewTransaction());
                    security.prepareManagement(principal.userId(), id);
                    UserAccount actor = auth.requireCurrentSession(principal);
                    Set<Long> scope = scope(actor, "IAM_USERS_VIEW");
                    UserAccount target = requireVisibleUser(id, scope);
                    UserView view = view(target);
                    security.audit("US02_DETAIL", actor.id, target.id, "SCOPED_READ");
                    return view;
                });
    }

    public Created createTemporary(String bearer, Temporary input) {
        long deptId = IdUtils.parse(input.deptId());
        var principal = jwt.verifyBearer(bearer);
        String normalized = SensitiveDataCodec.normalizePhone(input.phone());
        // Unrecoverable random secret is never returned and is never password history.
        try {
            return tx.execute(
                    status -> {
                        requireFreshTransaction(status.isNewTransaction());
                        security.prepareDepartmentManagement(principal.userId(), deptId);
                        UserAccount actor = auth.requireCurrentSession(principal);
                        requireScope(scope(actor, "IAM_USERS_WRITE"), deptId);
                        if (users.lockEligibleDepartment(deptId) == null)
                            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
                        UserAccount u = temporaryAccount(input, normalized, deptId, actor.id);
                        users.insertTemporary(u);
                        AAccountCreation.inserted(u);
                        security.signIdentity(u);
                        security.audit("US03_CREATE", actor.id, u.id, u.tempReason);
                        return new Created(
                                u.id.toString(),
                                u.username,
                                2,
                                mask(normalized),
                                u.deptId.toString(),
                                1);
                    });
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
    }

    private UserAccount temporaryAccount(
            Temporary input, String normalized, long deptId, long actorId) {
        // A persisted known name must remain usable by initialize/reset password checks.
        NamePinyin.variants(input.realName().strip());
        String placeholder =
                hasher.hash(
                        UUID.randomUUID().toString()
                                + UUID.randomUUID().toString().substring(0, 20));
        UserAccount u = new UserAccount();
        u.username =
                "temp"
                        + new java.math.BigInteger(
                                        UUID.randomUUID().toString().replace("-", ""), 16)
                                .toString();
        u.realNameCiphertext = codec.encrypt(input.realName().strip());
        u.phone = codec.encrypt(normalized);
        u.phoneHash = codec.phoneHash(normalized);
        u.passwordHash = placeholder;
        u.deptId = deptId;
        u.createdBy = actorId;
        u.tempReason = input.reason().strip();
        u.source = 2;
        u.status = 1;
        u.passwordInitialized = 0;
        u.sessionVersion = 1L;
        u.grantRevision = 1L;
        u.hrVersion = 0L;
        u.pwdFailedCount = 0;
        return u;
    }

    public StatusResult changeStatus(String bearer, String userId, Status input) {
        long id = IdUtils.parse(userId);
        var principal = jwt.verifyBearer(bearer);
        return tx.execute(
                status -> {
                    requireFreshTransaction(status.isNewTransaction());
                    security.prepareManagement(principal.userId(), id);
                    UserAccount actor = auth.requireCurrentSession(principal);
                    Set<Long> scope = scope(actor, "IAM_USERS_WRITE");
                    UserAccount target = requireVisibleUser(id, scope);
                    security.verifyIdentity(target);
                    if (target.source != 2
                            || target.status == 3
                            || input.status() == null
                            || input.status() < 1
                            || input.status() > 2)
                        throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
                    if (!target.status.equals(input.status())) {
                        if (input.status() == 2)
                            target.sessionVersion = Math.addExact(target.sessionVersion, 1);
                        target.status = input.status();
                        users.updateStatus(target);
                        security.signIdentity(target);
                        security.audit("US04_STATUS", actor.id, target.id, input.reason().strip());
                    }
                    return new StatusResult(target.id.toString(), target.status);
                });
    }

    private UserAccount requireVisibleUser(long userId, Set<Long> scope) {
        UserAccount user = users.lockById(userId);
        if (user == null || !scope.contains(user.deptId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return user;
    }

    private Set<Long> scope(UserAccount actor, String permission) {
        Set<Long> result = security.authorizeManagement(actor, permission);
        if (result == null
                || result.isEmpty()
                || result.stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return Set.copyOf(result);
    }

    private static void requireFreshTransaction(boolean fresh) {
        if (!fresh) throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }

    private static void requireScope(Set<Long> scope, long dept) {
        if (!scope.contains(dept)) throw new BusinessException(ErrorCode.OUT_OF_SCOPE);
    }

    private UserView view(UserAccount u) {
        security.verifyIdentity(u);
        return view(u, codec.decrypt(u.realNameCiphertext));
    }

    private UserView view(UserAccount u, String name) {
        String phone = SensitiveDataCodec.normalizePhone(codec.decrypt(u.phone));
        if (!codec.phoneHash(phone).equals(u.phoneHash))
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        return new UserView(
                u.id.toString(),
                u.username,
                u.employeeNo,
                name,
                mask(phone),
                u.deptId.toString(),
                u.status,
                u.passwordInitialized == 1,
                u.source);
    }

    /** Literal NFC matching in memory; no key, persisted index or cross-request cache. */
    private static String normalizeName(String value, ErrorCode invalid) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new BusinessException(invalid);
            } else if (Character.isLowSurrogate(c)) throw new BusinessException(invalid);
        }
        return java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFC);
    }

    private static String mask(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
