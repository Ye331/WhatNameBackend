package com.whatname.iam.service.oa;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.oa.OaDtos.Decision;
import com.whatname.iam.service.event.TrustedEventContext;
import org.springframework.stereotype.Service;
import com.whatname.iam.service.event.ExternalEventService;
import com.whatname.iam.service.grant.OaGrantOperations;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import static com.whatname.iam.common.api.ErrorCode.*;

@Service
public class OaDecisionService {
    private final ExternalEventService events;
    private final OaGrantOperations grants;
    public OaDecisionService(ExternalEventService events,OaGrantOperations grants) { this.events=events; this.grants=grants; }
    public Map<String,Object> decide(TrustedEventContext actor,Decision request) {
        validate(actor,request);
        var executed=new AtomicBoolean();
        var result=events.execute(actor,new ExternalEventService.Command(request.eventId(),"OA_DECISION",null,
                "USER",request.targetUserId(),request.approvedAt(),request),() -> {
            executed.set(true); return grants.apply(actor,request);
        });
        if(!executed.get()) {
            // A 重放原结果而不重复执行；仍检查服务当前范围，防止用旧范围越权读取。
            grants.requireReplayScope(actor,request);
            var replay=new LinkedHashMap<>(result); replay.put("idempotentReplay",true); return replay;
        }
        return result;
    }
    private void validate(TrustedEventContext actor,Decision r) {
        if(actor==null || !"OA".equals(actor.sourceSystem()) || actor.clientId()==null || actor.clientId().isBlank()
                || actor.requestId()==null || actor.requestId().isBlank()) throw new BusinessException(INVALID_SERVICE);
        if(r==null || !"OA".equals(r.sourceSystem()) || !"APPROVED".equals(r.approvalStatus()) || r.approvedAt()==null
                || !("GRANT".equals(r.operation()) || "REVOKE".equals(r.operation()))) invalid();
        text(r.eventId(),128); text(r.approvalId(),128); id(r.targetUserId()); id(r.roleId());
        if("GRANT".equals(r.operation())) { if(r.grantSourceId()!=null || r.originalApprovalId()!=null) invalid(); }
        else {
            if((r.grantSourceId()==null)==(r.originalApprovalId()==null)) invalid();
            if(r.grantSourceId()!=null) id(r.grantSourceId()); else text(r.originalApprovalId(),128);
        }
    }
    public static long id(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw new BusinessException(INVALID_ARGUMENT);
        try { return Long.parseLong(value); } catch(NumberFormatException error) { throw new BusinessException(INVALID_ARGUMENT); }
    }
    private void text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(Character::isISOControl)) invalid();
    }
    private static void invalid() { throw new BusinessException(INVALID_ARGUMENT); }
}
