package com.whatname.iam.service.auth;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.common.security.JwtTokenService.SessionPrincipal;
import com.whatname.iam.dto.auth.AuthRequests.*;
import com.whatname.iam.entity.auth.*;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.repository.auth.CredentialRepository;
import com.whatname.iam.repository.user.UserAccountRepository;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final CredentialRepository credentials;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;

    private final SmsCodeHasher codeHasher;
    private final SmsSender sender;
    private final JwtTokenService jwt;
    private final AccountSecurity security;
    private final TransactionTemplate tx;
    private final TransactionTemplate smsTx;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    public AuthService(
            UserAccountRepository users,
            CredentialRepository credentials,
            PasswordHasher hasher,
            PasswordPolicy policy,

            SmsCodeHasher codeHasher,
            SmsSender sender,
            JwtTokenService jwt,
            AccountSecurity security,
            PlatformTransactionManager manager) {
        this.users = users;
        this.credentials = credentials;
        this.hasher = hasher;
        this.policy = policy;
        this.codeHasher = codeHasher;
        this.sender = sender;
        this.jwt = jwt;
        this.security = security;
        tx = new TransactionTemplate(manager);
        // 认证自成事务：错误凭据先提交失败次数和日志，再返回失败。
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
        // 首次发送也要防并发重复；发生锁冲突时重试并重新检查发送间隔。
        smsTx = new TransactionTemplate(manager);
        smsTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        smsTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        smsTx.setTimeout(30);
    }

    public record LoginResult(String accessToken, String tokenType, int expiresIn, String userId) {
        @Override
        public String toString() {
            return "LoginResult[redacted]";
        }
    }

    public record SmsResult(boolean accepted, int retryAfterSeconds) {}

    public LoginResult login(Login input, String ip, String device) {
        String identifier = input.identifier().strip();
        String phoneLookup =
                input.loginType() == LoginType.PHONE
                        ? (PhoneNumbers.normalize(identifier))
                        : null;
        // 错误密码也要提交失败次数；事务结束后再抛出业务错误。
        Outcome<LoginResult> outcome =
                tx.execute(status -> authenticate(input, identifier, phoneLookup, ip, device));
        return outcome.unwrap();
    }

    private Outcome<LoginResult> authenticate(
            Login input, String identifier, String phoneLookup, String ip, String device) {
        UserAccount user =
                phoneLookup == null
                        ? users.lockByUsername(identifier)
                        : users.lockByPhone(phoneLookup);
        LocalDateTime now = now();
        if (user == null
                || user.status != 1
                || user.passwordInitialized != 1
                || (user.pwdLockedUntil != null && user.pwdLockedUntil.isAfter(now))) {
            log(user, input.loginType(), ip, device, false, "INVALID_CREDENTIALS");
            return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
        }
        security.verifyIdentity(user);
        if (user.pwdLockedUntil != null) {
            user.pwdLockedUntil = null;
            user.pwdFailedCount = 0;
        }
        var match = hasher.verify(input.password(), user.passwordHash);
        if (match == PasswordHasher.VerificationResult.INVALID_HASH
                || match == PasswordHasher.VerificationResult.VERIFICATION_FAILED)
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        if (match != PasswordHasher.VerificationResult.MATCH) {
            user.pwdFailedCount = Math.min(5, user.pwdFailedCount + 1);
            if (user.pwdFailedCount >= 5) user.pwdLockedUntil = now.plusMinutes(15);
            users.updateLoginState(user);
            log(user, input.loginType(), ip, device, false, "INVALID_CREDENTIALS");
            return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
        }
        String token = jwt.issue(user.id, user.sessionVersion);
        user.pwdFailedCount = 0;
        user.pwdLockedUntil = null;
        user.lastLoginAt = now;
        users.updateLoginState(user);
        log(user, input.loginType(), ip, device, true, null);
        security.audit("AU01_LOGIN", user.id, user.id, "SUCCESS");
        return Outcome.ok(new LoginResult(token, "Bearer", 3600, user.id.toString()));
    }

    public SmsResult sendSms(SendSms input) {
        String phone = PhoneNumbers.normalize(input.phone());
        String purpose = input.purpose().name();
        sender.requireAvailable();
        for (int attempt = 0; ; attempt++) {
            try {
                sendSmsTransaction(input, phone, purpose);
                return new SmsResult(true, 60);
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= 2) throw e;
            }
        }
    }

    private void sendSmsTransaction(SendSms input, String phone, String purpose) {
        smsTx.executeWithoutResult(
                status -> {
                    UserAccount user = users.lockByPhone(phone);
                    LocalDateTime now = now();
                    SmsCode previous = credentials.lockLatestCode(phone, purpose);
                    if (previous != null && previous.createdAt.plusSeconds(60).isAfter(now))
                        throw new BusinessException(ErrorCode.RATE_LIMITED);
                    credentials.invalidateCodes(phone, purpose, now);
                    String code;
                    do {
                        code = "%06d".formatted(random.nextInt(1_000_000));
                    } while (previous != null
                            && codeHasher.matches(phone, purpose, code, previous.codeHash));
                    SmsCode entry = new SmsCode();
                    entry.phone = phone;
                    entry.purpose = purpose;
                    entry.codeHash = codeHasher.hash(phone, purpose, code);
                    entry.createdAt = now;
                    entry.expiresAt = now.plusMinutes(5);
                    credentials.insertCode(entry);
                    // 未知手机号也记录相同的限流状态，避免通过响应判断账号是否存在。
                    if (user != null
                            && user.status == 1
                            && (input.purpose() == Purpose.RESET_PASSWORD
                                    || user.passwordInitialized == 0)) {
                        security.verifyIdentity(user);
                        sender.send(phone, purpose, code);
                    }
                    security.audit("AU05_SMS", null, user == null ? null : user.id, purpose);
                });
    }

    public void changePassword(Password input, boolean initialize) {
        String phone = PhoneNumbers.normalize(input.phone());
        String purpose = initialize ? Purpose.SET_PASSWORD.name() : Purpose.RESET_PASSWORD.name();
        Outcome<Void> result =
                tx.execute(
                        status -> {
                            UserAccount user = users.lockByPhone(phone);
                            SmsCode code = credentials.lockLatestCode(phone, purpose);
                            LocalDateTime now = now();
                            if (code == null
                                    || code.consumedAt != null
                                    || code.attempts >= 5
                                    || !code.expiresAt.isAfter(now))
                                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
                            if (!codeHasher.matches(
                                    phone, purpose, input.code(), code.codeHash)) {
                                credentials.failCode(code.id, now);
                                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
                            }
                            if (user == null
                                    || user.status != 1
                                    || (initialize && user.passwordInitialized != 0))
                                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
                            security.verifyIdentity(user);
                            validateNewPassword(user, phone, input.newPassword());
                            return savePassword(user, code, input.newPassword(), initialize, now);
                        });
        result.unwrap();
    }

    /** 验证码消费、密码、历史、会话版本、签名和审计在同一事务中提交。 */
    private Outcome<Void> savePassword(
            UserAccount user,
            SmsCode code,
            String newPassword,
            boolean initialize,
            LocalDateTime now) {
        user.passwordHash = hasher.hash(newPassword);
        user.passwordInitialized = 1;
        if (!initialize) user.sessionVersion = Math.addExact(user.sessionVersion, 1);
        if (credentials.consumeCode(code.id, now) != 1)
            return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
        users.updatePassword(user);
        PasswordHistory h = new PasswordHistory();
        h.userId = user.id;
        h.passwordHash = user.passwordHash;
        credentials.insertHistory(h);
        credentials.trimHistory(user.id);
        security.signIdentity(user);
        security.audit(
                initialize ? "AU07_INITIALIZE" : "AU08_RESET", user.id, user.id, "SMS_PROOF");
        return Outcome.ok(null);
    }

    /** 先核对身份和历史，再检查新密码，校验失败不消耗验证码。 */
    private void validateNewPassword(UserAccount user, String phone, String newPassword) {
        String actualPhone = PhoneNumbers.normalize(user.phone);
        if (!phone.equals(actualPhone)) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        List<String> history =
                credentials.recentHistory(user.id).stream().map(h -> h.passwordHash).toList();
        if (user.passwordInitialized == 1
                && (history.isEmpty() || !history.getFirst().equals(user.passwordHash)))
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        List<String> pinyins = NamePinyin.variants(user.realName);
        var identity =
                new PasswordPolicy.IdentityContext(user.username, pinyins.getFirst(), actualPhone);
        var validation = policy.validate(newPassword, identity, history);
        switch (validation) {
            case VALID -> {}
            case IDENTITY_CONTEXT_REQUIRED, HISTORY_REQUIRED, INVALID_HISTORY ->
                    throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
            case HISTORY_VERIFICATION_FAILED ->
                    throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
            default -> throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        String folded = newPassword.toLowerCase(Locale.ROOT);
        if (pinyins.stream().anyMatch(folded::contains))
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    /** 在管理事务中调用：锁住用户并读取当前状态，避免使用过期会话。 */
    public UserAccount requireCurrentSession(SessionPrincipal principal) {
        UserAccount user = users.lockById(principal.userId());
        if (user == null
                || user.status != 1
                || user.passwordInitialized != 1
                || user.sessionVersion != principal.sessionVersion()
                || credentials.findInvalidation(principal.jti()) != null)
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        security.verifyIdentity(user);
        return user;
    }

    public void logout(String authorization) {
        SessionPrincipal p = jwt.verifyBearer(authorization);
        tx.executeWithoutResult(
                status -> {
                    // 与停用/改密串行；旧令牌只允许注销自身，不能操作其他会话。
                    UserAccount current = users.lockById(p.userId());
                    if (current == null) throw new BusinessException(ErrorCode.INVALID_TOKEN);
                    security.verifyIdentity(current);
                    SessionInvalidation existing = credentials.findInvalidation(p.jti());
                    if (existing != null) {
                        if (existing.userId != p.userId())
                            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
                        return;
                    }
                    SessionInvalidation row = new SessionInvalidation();
                    row.userId = p.userId();
                    row.jti = p.jti();
                    row.expiresAt = LocalDateTime.ofInstant(p.expiresAt(), clock.getZone());
                    credentials.insertInvalidation(row);
                    security.audit("AU04_LOGOUT", p.userId(), p.userId(), "LOGOUT");
                });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private void log(
            UserAccount user,
            LoginType type,
            String ip,
            String device,
            boolean success,
            String reason) {
        LoginLog l = new LoginLog();
        l.userId = user == null ? null : user.id;
        l.username = user == null ? null : user.username;
        l.loginType = type == LoginType.USERNAME ? 1 : 2;
        l.loginIp = truncate(ip, 64);
        l.loginDevice = truncate(device, 256);
        l.loginStatus = success ? 1 : 0;
        l.failReason = reason;
        credentials.insertLoginLog(l);
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    private record Outcome<T>(T value, ErrorCode error) {
        static <T> Outcome<T> ok(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> error(ErrorCode error) {
            return new Outcome<>(null, error);
        }

        T unwrap() {
            if (error != null) throw new BusinessException(error);
            return value;
        }
    }
}
