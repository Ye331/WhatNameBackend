package com.whatname.iam.controller.hr;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.hr.HrRequests;
import com.whatname.iam.service.department.DepartmentCollaboration;
import com.whatname.iam.service.hr.HrService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/hr")
public class HrController {
    private final HrService service;
    private final DepartmentCollaboration security;
    public HrController(HrService service,DepartmentCollaboration security) { this.service=service;this.security=security; }
    @PutMapping("/employees/{sgNo}")
    public ApiResponse<Map<String,Object>> employee(HttpServletRequest request,@PathVariable String sgNo,
            @Valid @RequestBody HrRequests.Employee body) {
        return ApiResponse.ok(service.employee(security.authenticateHr(request,"HR_EMPLOYEE"),sgNo,body));
    }
    @PostMapping("/transfers")
    public ApiResponse<Map<String,Object>> transfer(HttpServletRequest request,@Valid @RequestBody HrRequests.Transfer body) {
        return ApiResponse.ok(service.transfer(security.authenticateHr(request,"HR_TRANSFER"),body));
    }
    @PostMapping("/terminations")
    public ApiResponse<Map<String,Object>> terminate(HttpServletRequest request,@Valid @RequestBody HrRequests.Termination body) {
        return ApiResponse.ok(service.terminate(security.authenticateHr(request,"HR_TERMINATION"),body));
    }
}
