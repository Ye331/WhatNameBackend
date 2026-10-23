package com.whatname.iam.repository.permission;

import com.whatname.iam.service.permission.SignedDataType;
import java.util.List;
import java.util.Map;

public interface SignatureRepositoryOperations {
    Map<String, Object> record(SignedDataType type, long id);

    List<Map<String, Object>> records(SignedDataType type, List<Long> ids);

    List<Map<String, Object>> signatures(String type, List<Long> ids);

    List<Map<String, Object>> menuApiMembers(List<Long> ids);

    List<Long> userRoles(long id);

    List<Map<String, Object>> sources(long id);

    List<Long> roleMenus(long id);

    List<Long> menuApis(long id);

    List<Long> delegableRoles(long id);

    List<Long> applicationMenus(long id);

    List<Long> applicationApis(long id);

    List<Map<String, Object>> organization();

    Map<String, Object> signature(String type, long id);

    void save(String type, long id, String signature, String keyId);

}
