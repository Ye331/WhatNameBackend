package com.whatname.iam.service.user;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.dto.common.PageResponse;
import com.whatname.iam.dto.user.UserRequests.*;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.auth.NamePinyin;
import com.whatname.iam.service.auth.PasswordHasher;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserService {
    private final UserAccountMapper users;
    private final AuthService auth;
    private final JwtTokenService jwt;
    private final SensitiveDataCodec codec;
    private final M1SecurityGateway security;
    private final PasswordHasher hasher;
    private static final int SEARCH_BATCH_SIZE=200;
    private final TransactionTemplate tx;
    public UserService(UserAccountMapper users,AuthService auth,JwtTokenService jwt,SensitiveDataCodec codec,
                         M1SecurityGateway security,PasswordHasher hasher,PlatformTransactionManager manager) {
        this.users=users;this.auth=auth;this.jwt=jwt;this.codec=codec;this.security=security;this.hasher=hasher;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(30);
    }
    public record UserView(String userId,String username,String sgNo,String realName,String phoneMasked,
                            String deptId,int status,boolean passwordInitialized,int source) {}
    public record Created(String userId,String username,int source,String phoneMasked,String deptId,int status) {}
    public record StatusResult(String userId,int status) {}

    public PageResponse<UserView> list(String bearer,String keyword,String deptId,Integer accountStatus,Integer source,int page,int pageSize) {
        if(page<1 || page>1_000_000 || pageSize<1 || pageSize>500 || (keyword!=null && keyword.length()>64)
                || (accountStatus!=null && (accountStatus<1 || accountStatus>3))
                || (source!=null && (source<1 || source>3))) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        Long department=deptId==null?null:parseId(deptId);
        String term=keyword==null || keyword.isBlank()?null:keyword.strip();
        String phoneHash=null;
        if(term!=null && term.matches("(?:\\+86)?1[3-9][0-9]{9}")) phoneHash=codec.phoneHash(SensitiveDataCodec.normalizePhone(term));
        final String phone=phoneHash;
        var principal=jwt.verifyBearer(bearer);
        return tx.execute(status->{
            requireFreshTransaction(status.isNewTransaction());
            security.prepareManagement(principal.userId());
            UserAccount actor=auth.requireCurrentSession(principal);
            Set<Long> scope=scope(actor,"IAM_USERS_VIEW");
            if(department!=null) requireScope(scope,department);
            List<UserView> items;
            int count;
            long offset=((long)page-1)*pageSize;
            if(term==null) {
                count=users.count(scope,department,null,null,accountStatus,source);
                items=users.list(scope,department,null,null,accountStatus,source,offset,pageSize).stream().map(this::view).toList();
            } else {
                String nameTerm=normalizeName(term,ErrorCode.INVALID_ARGUMENT);
                items=new ArrayList<>(pageSize);count=0;
                long afterId=0;
                // All batches use this transaction's repeatable-read snapshot. Never prefilter
                // on identifiers: an otherwise nonmatching row can still match its encrypted name.
                while(true) {
                    List<UserAccount> batch=users.searchCandidates(scope,department,term,phone,accountStatus,source,afterId,SEARCH_BATCH_SIZE);
                    for(UserAccount u:batch) {
                        security.verifyIdentity(u);
                        String name=codec.decrypt(u.realNameCiphertext);
                        if(Boolean.TRUE.equals(u.identifierMatched) || normalizeName(name,ErrorCode.INTEGRITY_FAILURE).contains(nameTerm)) {
                            if(count>=offset && items.size()<pageSize) items.add(view(u,name));
                            count=Math.addExact(count,1);
                        }
                        afterId=u.id;
                    }
                    if(batch.size()<SEARCH_BATCH_SIZE) break;
                }
            }
            security.audit("US01_LIST",actor.id,null,"SCOPED_READ");
            return new PageResponse<>(items,page,pageSize,count);
        });
    }

    public UserView detail(String bearer,String userId) {
        long id=parseId(userId);var principal=jwt.verifyBearer(bearer);
        return tx.execute(status->{
            requireFreshTransaction(status.isNewTransaction());
            security.prepareManagement(principal.userId(),id);
            UserAccount actor=auth.requireCurrentSession(principal);
            Set<Long> scope=scope(actor,"IAM_USERS_VIEW");
            UserAccount target=users.lockById(id);
            if(target==null || !scope.contains(target.deptId)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            UserView view=view(target);
            security.audit("US02_DETAIL",actor.id,target.id,"SCOPED_READ");return view;
        });
    }

    public Created createTemporary(String bearer,Temporary input) {
        long deptId=parseId(input.deptId());var principal=jwt.verifyBearer(bearer);
        String normalized=SensitiveDataCodec.normalizePhone(input.phone());
        // Unrecoverable random secret is never returned and is never password history.
        try {
            return tx.execute(status->{
                requireFreshTransaction(status.isNewTransaction());
                security.prepareDepartmentManagement(principal.userId(),deptId);
                UserAccount actor=auth.requireCurrentSession(principal);
                requireScope(scope(actor,"IAM_USERS_WRITE"),deptId);
                if(users.lockEligibleDepartment(deptId)==null) throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
                // A persisted known name must remain usable by initialize/reset password checks.
                NamePinyin.variants(input.realName().strip());
                String placeholder=hasher.hash(UUID.randomUUID().toString()+UUID.randomUUID().toString().substring(0,20));
                UserAccount u=new UserAccount();
                u.username="temp"+new java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).toString();
                u.realNameCiphertext=codec.encrypt(input.realName().strip());u.phone=codec.encrypt(normalized);
                u.phoneHash=codec.phoneHash(normalized);u.passwordHash=placeholder;u.deptId=deptId;
                u.createdBy=actor.id;u.tempReason=input.reason().strip();u.source=2;u.status=1;u.passwordInitialized=0;
                u.sessionVersion=1L;u.grantRevision=1L;u.hrVersion=0L;u.pwdFailedCount=0;
                users.insertTemporary(u);
                AAccountCreation.inserted(u);
                security.signIdentity(u);security.audit("US03_CREATE",actor.id,u.id,u.tempReason);
                return new Created(u.id.toString(),u.username,2,mask(normalized),u.deptId.toString(),1);
            });
        } catch(DuplicateKeyException e) {throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);}
    }

    public StatusResult changeStatus(String bearer,String userId,Status input) {
        long id=parseId(userId);var principal=jwt.verifyBearer(bearer);
        return tx.execute(status->{
            requireFreshTransaction(status.isNewTransaction());
            security.prepareManagement(principal.userId(),id);
            UserAccount actor=auth.requireCurrentSession(principal);
            Set<Long> scope=scope(actor,"IAM_USERS_WRITE");
            UserAccount target=users.lockById(id);
            if(target==null || !scope.contains(target.deptId)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            security.verifyIdentity(target);
            if(target.source!=2 || target.status==3 || input.status()==null || input.status()<1 || input.status()>2)
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            if(!target.status.equals(input.status())) {
                if(input.status()==2) target.sessionVersion=Math.addExact(target.sessionVersion,1);
                target.status=input.status();users.updateStatus(target);security.signIdentity(target);
                security.audit("US04_STATUS",actor.id,target.id,input.reason().strip());
            }
            return new StatusResult(target.id.toString(),target.status);
        });
    }

    private Set<Long> scope(UserAccount actor,String permission) {
        Set<Long> result=security.authorizeManagement(actor,permission);
        if(result==null || result.isEmpty() || result.stream().anyMatch(id->id==null || id<=0))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return Set.copyOf(result);
    }
    private static void requireFreshTransaction(boolean fresh) {
        if(!fresh) throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
    private static void requireScope(Set<Long> scope,long dept) {if(!scope.contains(dept))throw new BusinessException(ErrorCode.OUT_OF_SCOPE);}
    private UserView view(UserAccount u) {
        security.verifyIdentity(u);
        return view(u,codec.decrypt(u.realNameCiphertext));
    }
    private UserView view(UserAccount u,String name) {
        String phone=SensitiveDataCodec.normalizePhone(codec.decrypt(u.phone));
        if(!codec.phoneHash(phone).equals(u.phoneHash)) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        return new UserView(u.id.toString(),u.username,u.employeeNo,name,mask(phone),u.deptId.toString(),u.status,u.passwordInitialized==1,u.source);
    }
    /** Literal NFC matching in memory; no key, persisted index or cross-request cache. */
    private static String normalizeName(String value,ErrorCode invalid) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new BusinessException(invalid);
            } else if(Character.isLowSurrogate(c)) throw new BusinessException(invalid);
        }
        return java.text.Normalizer.normalize(value.strip(),java.text.Normalizer.Form.NFC);
    }
    private static String mask(String phone){return phone.substring(0,3)+"****"+phone.substring(7);}
    public static long parseId(String value) {
        try {if(value==null || !value.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}
        catch(RuntimeException e){throw new BusinessException(ErrorCode.INVALID_ARGUMENT);}
    }
}
