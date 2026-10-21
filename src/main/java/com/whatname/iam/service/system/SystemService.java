package com.whatname.iam.service.system;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.system.SystemStatus;
import com.whatname.iam.mapper.system.SystemMapper;

import org.springframework.stereotype.Service;

@Service
public class SystemService {
    private final SystemMapper mapper;

    public SystemService(SystemMapper mapper) {
        this.mapper = mapper;
    }

    public SystemStatus applicationStatus() {
        return new SystemStatus("iam-backend", "UP");
    }

    public SystemStatus databaseStatus() {
        if (mapper.ping() != 1) {
            throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
        return new SystemStatus("mysql", "UP");
    }
}
