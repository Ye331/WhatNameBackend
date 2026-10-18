package com.whatname.iam.service.auth;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.common.security.JwtTokenService.SessionPrincipal;
import com.whatname.iam.dto.auth.AuthRequests.*;
import com.whatname.iam.entity.auth.*;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.auth.CredentialMapper;
import com.whatname.iam.mapper.user.UserAccountMapper;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private final UserAccountMapper users;
    private final CredentialMapper credentials;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final SensitiveDataCodec codec;
    private final SmsCodeHasher codeHasher;
    private final SmsSender sender;
    private final JwtTokenService jwt;
    private final M1SecurityGateway security;
    private final TransactionTemplate tx;
    private final TransactionTemplate smsTx;
    private final SecureRandom random=new SecureRandom();
    private final Clock clock=Clock.system(ZoneId.of("Asia/Shanghai"));

    public AuthService(UserAccountMapper users,CredentialMapper credentials,PasswordHasher hasher,
                        PasswordPolicy policy,SensitiveDataCodec codec,SmsCodeHasher codeHasher,
                        SmsSender sender,JwtTokenService jwt,M1SecurityGateway security,PlatformTransactionManager manager) {
        this.users=users; this.credentials=credentials; this.hasher=hasher; this.policy=policy; this.codec=codec;
        this.codeHasher=codeHasher; this.sender=sender; this.jwt=jwt; this.security=security;
        tx=new TransactionTemplate(manager);
        // HTTP authentication is a complete unit. A bad proof returns only after this transaction
        // commits its counter/log; no failure exception is thrown inside that transaction.
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
        // Missing phone/code rows also need InnoDB gap protection. A concurrent first send
        // can lose a gap-lock upgrade; retry the whole transaction and recheck the interval.
        smsTx=new TransactionTemplate(manager);
        smsTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        smsTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        smsTx.setTimeout(30);
    }

    public record LoginResult(String accessToken,String tokenType,int expiresIn,String userId) {
        @Override public String toString(){return "LoginResult[redacted]";}
    }
    public record SmsResult(boolean accepted,int retryAfterSeconds) {}

    public LoginResult login(Login input,String ip,String device) {
        String identifier=input.identifier().strip();
        String phoneHash=input.loginType()==LoginType.PHONE ? codec.phoneHash(SensitiveDataCodec.normalizePhone(identifier)):null;
        Outcome<LoginResult> outcome=tx.execute(status->{
            UserAccount u=phoneHash==null?users.lockByUsername(identifier):users.lockByPhoneHash(phoneHash);
            LocalDateTime now=now();
            if(u==null || u.status!=1 || u.passwordInitialized!=1 || (u.pwdLockedUntil!=null && u.pwdLockedUntil.isAfter(now))) {
                log(u,input.loginType(),ip,device,false,"INVALID_CREDENTIALS");
                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
            }
            security.verifyIdentity(u);
            if(u.pwdLockedUntil!=null) {u.pwdLockedUntil=null;u.pwdFailedCount=0;}
            var match=hasher.verify(input.password(),u.passwordHash);
            if(match==PasswordHasher.VerificationResult.INVALID_HASH || match==PasswordHasher.VerificationResult.VERIFICATION_FAILED)
                throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
            if(match!=PasswordHasher.VerificationResult.MATCH) {
                u.pwdFailedCount=Math.min(5,u.pwdFailedCount+1);
                if(u.pwdFailedCount>=5) u.pwdLockedUntil=now.plusMinutes(15);
                users.updateLoginState(u);
                log(u,input.loginType(),ip,device,false,"INVALID_CREDENTIALS");
                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
            }
            String token=jwt.issue(u.id,u.sessionVersion);
            u.pwdFailedCount=0;u.pwdLockedUntil=null;u.lastLoginAt=now;
            users.updateLoginState(u);
            log(u,input.loginType(),ip,device,true,null);
            security.audit("AU01_LOGIN",u.id,u.id,"SUCCESS");
            return Outcome.ok(new LoginResult(token,"Bearer",3600,u.id.toString()));
        });
        return outcome.unwrap();
    }

    public SmsResult sendSms(SendSms input) {
        String phone=SensitiveDataCodec.normalizePhone(input.phone()), phoneHash=codec.phoneHash(phone);
        String purpose=input.purpose().name();
        sender.requireAvailable();
        for(int attempt=0;;attempt++) {
            try {
                sendSmsTransaction(input,phone,phoneHash,purpose);
                return new SmsResult(true,60);
            } catch(PessimisticLockingFailureException e) {
                if(attempt>=2) throw e;
            }
        }
    }

    private void sendSmsTransaction(SendSms input,String phone,String phoneHash,String purpose) {
        smsTx.executeWithoutResult(status->{
            UserAccount u=users.lockByPhoneHash(phoneHash);
            LocalDateTime now=now();
            SmsCode previous=credentials.lockLatestCode(phoneHash,purpose);
            if(previous!=null && previous.createdAt.plusSeconds(60).isAfter(now)) throw new BusinessException(ErrorCode.RATE_LIMITED);
            credentials.invalidateCodes(phoneHash,purpose,now);
            String code;
            do {code="%06d".formatted(random.nextInt(1_000_000));}
            while(previous!=null && codeHasher.matches(phoneHash,purpose,code,previous.codeHash));
            SmsCode entry=new SmsCode();entry.phoneHash=phoneHash;entry.purpose=purpose;
            entry.codeHash=codeHasher.hash(phoneHash,purpose,code);entry.createdAt=now;entry.expiresAt=now.plusMinutes(5);
            credentials.insertCode(entry);
            // Persist the same opaque response/rate-limit state for unknown/ineligible phones.
            // Do not send a usable proof to an ineligible account.
            if(u!=null && u.status==1 && (input.purpose()==Purpose.RESET_PASSWORD || u.passwordInitialized==0)) {
                security.verifyIdentity(u);
                sender.send(phone,purpose,code);
            }
            security.audit("AU05_SMS",null,u==null?null:u.id,purpose);
        });
    }

    public void changePassword(Password input,boolean initialize) {
        String phone=SensitiveDataCodec.normalizePhone(input.phone()),phoneHash=codec.phoneHash(phone);
        String purpose=initialize?Purpose.SET_PASSWORD.name():Purpose.RESET_PASSWORD.name();
        Outcome<Void> result=tx.execute(status->{
            UserAccount u=users.lockByPhoneHash(phoneHash);
            SmsCode code=credentials.lockLatestCode(phoneHash,purpose);
            LocalDateTime now=now();
            if(code==null || code.consumedAt!=null || code.attempts>=5 || !code.expiresAt.isAfter(now))
                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
            if(!codeHasher.matches(phoneHash,purpose,input.code(),code.codeHash)) {
                credentials.failCode(code.id,now);
                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
            }
            if(u==null || u.status!=1 || (initialize && u.passwordInitialized!=0))
                return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
            security.verifyIdentity(u);
            String actualPhone=SensitiveDataCodec.normalizePhone(codec.decrypt(u.phone));
            if(!phone.equals(actualPhone)) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
            List<String> history=credentials.recentHistory(u.id).stream().map(h->h.passwordHash).toList();
            if(u.passwordInitialized==1 && (history.isEmpty() || !history.getFirst().equals(u.passwordHash)))
                throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
            List<String> pinyins=NamePinyin.variants(codec.decrypt(u.realNameCiphertext));
            var identity=new PasswordPolicy.IdentityContext(u.username,pinyins.getFirst(),actualPhone);
            var validation=policy.validate(input.newPassword(),identity,history);
            switch(validation) {
                case VALID -> { }
                case IDENTITY_CONTEXT_REQUIRED, HISTORY_REQUIRED, INVALID_HISTORY ->
                    throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
                case HISTORY_VERIFICATION_FAILED -> throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
                default -> throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            }
            String folded=input.newPassword().toLowerCase(Locale.ROOT);
            if(pinyins.stream().anyMatch(folded::contains)) throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            u.passwordHash=hasher.hash(input.newPassword());u.passwordInitialized=1;
            if(!initialize) u.sessionVersion=Math.addExact(u.sessionVersion,1);
            if(credentials.consumeCode(code.id,now)!=1) return Outcome.error(ErrorCode.INVALID_CREDENTIALS);
            users.updatePassword(u);
            PasswordHistory h=new PasswordHistory();h.userId=u.id;h.passwordHash=u.passwordHash;
            credentials.insertHistory(h);credentials.trimHistory(u.id);
            security.signIdentity(u);
            security.audit(initialize?"AU07_INITIALIZE":"AU08_RESET",u.id,u.id,"SMS_PROOF");
            return Outcome.ok(null);
        });
        result.unwrap();
    }

    /** Must be called inside the management transaction; reads current state under the user lock. */
    public UserAccount requireCurrentSession(SessionPrincipal principal) {
        UserAccount u=users.lockById(principal.userId());
        if(u==null || u.status!=1 || u.passwordInitialized!=1 || u.sessionVersion!=principal.sessionVersion()
                || credentials.findInvalidation(principal.jti())!=null) throw new BusinessException(ErrorCode.INVALID_TOKEN);
        security.verifyIdentity(u);
        return u;
    }

    public void logout(String authorization) {
        SessionPrincipal p=jwt.verifyBearer(authorization);
        tx.executeWithoutResult(status->{
            // Serialize same-user logout and status/password transitions. State/sv is deliberately
            // not an eligibility gate here; a signed old token can only revoke its own jti.
            if(users.lockById(p.userId())==null) throw new BusinessException(ErrorCode.INVALID_TOKEN);
            SessionInvalidation existing=credentials.findInvalidation(p.jti());
            if(existing!=null) {
                if(existing.userId!=p.userId()) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
                return;
            }
            SessionInvalidation row=new SessionInvalidation();row.userId=p.userId();row.jti=p.jti();
            row.expiresAt=LocalDateTime.ofInstant(p.expiresAt(),clock.getZone());
            credentials.insertInvalidation(row);
            security.audit("AU04_LOGOUT",p.userId(),p.userId(),"LOGOUT");
        });
    }

    private LocalDateTime now(){return LocalDateTime.now(clock).withNano(0);}
    private void log(UserAccount u,LoginType type,String ip,String device,boolean success,String reason) {
        LoginLog l=new LoginLog();l.userId=u==null?null:u.id;l.username=u==null?null:u.username;
        l.loginType=type==LoginType.USERNAME?1:2;l.loginIp=truncate(ip,64);l.loginDevice=truncate(device,256);
        l.loginStatus=success?1:0;l.failReason=reason;credentials.insertLoginLog(l);
    }
    private static String truncate(String value,int max){return value==null?null:value.substring(0,Math.min(value.length(),max));}
    private record Outcome<T>(T value,ErrorCode error) {
        static <T> Outcome<T> ok(T value){return new Outcome<>(value,null);}
        static <T> Outcome<T> error(ErrorCode error){return new Outcome<>(null,error);}
        T unwrap(){if(error!=null)throw new BusinessException(error);return value;}
    }
}
