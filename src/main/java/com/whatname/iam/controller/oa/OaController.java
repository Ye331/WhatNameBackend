package com.whatname.iam.controller.oa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.oa.OaDtos.Decision;
import com.whatname.iam.service.oa.OaDecisionService;
import com.whatname.iam.service.permission.TrustedOaCaller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

@RestController
@RequestMapping("/api/v1/integrations/oa")
public class OaController {
    private final OaDecisionService service;
    private final TrustedOaCaller callers;
    private final ObjectMapper json;
    public OaController(OaDecisionService service,TrustedOaCaller callers,ObjectMapper json) {
        this.service=service; this.callers=callers; this.json=json;
    }
    @PostMapping(value="/decisions",consumes="application/json")
    public ApiResponse<Map<String,Object>> decide(HttpServletRequest request,@RequestBody byte[] rawBody) {
        if(rawBody==null || rawBody.length>16384) throw new BusinessException(INVALID_ARGUMENT);
        var actor=callers.authenticate(request,rawBody);
        Decision decision;
        try {
            com.fasterxml.jackson.databind.JsonNode body=json.readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(rawBody);
            if(body==null || !body.isObject() || !body.path("approvedAt").isTextual()
                    || (body.has("grantSourceId") && !body.path("grantSourceId").isTextual())
                    || (body.has("originalApprovalId") && !body.path("originalApprovalId").isTextual()))
                throw new BusinessException(INVALID_ARGUMENT);
            decision=json.treeToValue(body,Decision.class);
        }
        catch(Exception error) { throw new BusinessException(INVALID_ARGUMENT); }
        return ApiResponse.ok(service.decide(actor,decision));
    }
}
