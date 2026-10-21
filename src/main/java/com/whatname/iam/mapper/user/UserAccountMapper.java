package com.whatname.iam.mapper.user;

import com.whatname.iam.entity.user.UserAccount;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

public interface UserAccountMapper {
    UserAccount findById(@Param("id") long id);

    UserAccount lockById(@Param("id") long id);

    UserAccount lockByUsername(@Param("username") String username);

    UserAccount lockByPhoneHash(@Param("phoneHash") String phoneHash);

    UserAccount lockByEmployeeNo(@Param("sgNo") String sgNo);

    int insertHr(UserAccount user);

    int updateHr(UserAccount user);

    int insertTemporary(UserAccount user);

    int updatePassword(UserAccount user);

    int updateLoginState(UserAccount user);

    int updateStatus(UserAccount user);

    Long lockEligibleDepartment(@Param("id") long id);

    List<UserAccount> list(
            @Param("scope") Set<Long> scope,
            @Param("deptId") Long deptId,
            @Param("keyword") String keyword,
            @Param("phoneHash") String phoneHash,
            @Param("accountStatus") Integer accountStatus,
            @Param("source") Integer source,
            @Param("offset") long offset,
            @Param("limit") int limit);

    int count(
            @Param("scope") Set<Long> scope,
            @Param("deptId") Long deptId,
            @Param("keyword") String keyword,
            @Param("phoneHash") String phoneHash,
            @Param("accountStatus") Integer accountStatus,
            @Param("source") Integer source);

    List<UserAccount> searchCandidates(
            @Param("scope") Set<Long> scope,
            @Param("deptId") Long deptId,
            @Param("keyword") String keyword,
            @Param("phoneHash") String phoneHash,
            @Param("accountStatus") Integer accountStatus,
            @Param("source") Integer source,
            @Param("afterId") long afterId,
            @Param("limit") int limit);
}
