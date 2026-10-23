package com.whatname.iam.repository.user;

import com.whatname.iam.entity.user.UserAccount;
import java.util.List;
import java.util.Set;

public interface UserAccountRepositoryOperations {
    UserAccount findById(long id);

    UserAccount lockById(long id);

    UserAccount lockByUsername(String username);

    UserAccount lockByPhone(String phoneLookup);

    UserAccount lockByEmployeeNo(String sgNo);

    int insertHr(UserAccount user);

    int updateHr(UserAccount user);

    int insertTemporary(UserAccount user);

    int updatePassword(UserAccount user);

    int updateLoginState(UserAccount user);

    int updateStatus(UserAccount user);

    Long lockEligibleDepartment(long id);

    List<UserAccount> list(Set<Long> scope, Long deptId, String keyword, String phoneLookup, Integer accountStatus, Integer source, long offset, int limit);

    int count(Set<Long> scope, Long deptId, String keyword, String phoneLookup, Integer accountStatus, Integer source);

    List<UserAccount> searchCandidates(Set<Long> scope, Long deptId, String keyword, String phoneLookup, Integer accountStatus, Integer source, long afterId, int limit);

}
