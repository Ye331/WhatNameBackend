package com.whatname.iam.service.user;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.user.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

@Service
public class NameSearchIndex {
    private final NameSearchMapper index;
    private final UserAccountMapper users;
    private final NameSearchTokens tokens;
    private final SensitiveDataCodec codec;
    private final M1SecurityGateway security;
    private final TransactionTemplate tx;
    public NameSearchIndex(NameSearchMapper index,UserAccountMapper users,NameSearchTokens tokens,
            SensitiveDataCodec codec,M1SecurityGateway security,PlatformTransactionManager manager) {
        this.index=index;this.users=users;this.tokens=tokens;this.codec=codec;this.security=security;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(30);
    }
    /** Caller owns the user row lock (or just inserted the row); joins its business transaction. */
    public void replace(UserAccount user,String plaintext) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Transaction required");
        Set<String> values=tokens.all(plaintext);
        index.deleteTokens(user.id);
        index.insertTokens(user.id,values);
        index.putState(user.id,tokens.generation(),user.realNameCiphertext,values.size());
    }
    /** Explicit maintenance only. Stable ID range, bounded batch; one atomic row per transaction.
     * Verified identity before decrypt; a failed row does not advance the returned checkpoint.
     * Re-running the last batch is safe, and also verifies the complete token set after replacement.
     */
    public long backfillBatch(long after,long through,int limit) {
        if(after<0 || through<after || limit<1 || limit>100) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        tokens.generation();
        List<Long> ids=index.batchIds(after,through,limit);
        long cursor=after;
        for(long id:ids) {
            tx.executeWithoutResult(status->{
                UserAccount user=users.lockById(id);
                if(user==null) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
                security.verifyIdentity(user);
                String name=codec.decrypt(user.realNameCiphertext);
                replace(user,name);
                if(!tokens.all(name).equals(new TreeSet<>(index.storedTokens(id))))
                    throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
                security.audit("NAME_INDEX_BACKFILL",null,id,"CONTROLLED_LOCAL_MAINTENANCE");
            });
            cursor=id;
        }
        return cursor;
    }
}
