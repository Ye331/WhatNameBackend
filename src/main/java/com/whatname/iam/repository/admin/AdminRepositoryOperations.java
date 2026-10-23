package com.whatname.iam.repository.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import java.util.List;

public interface AdminRepositoryOperations {
    List<Appointment> selectAppointments(boolean lock);

    List<Department> selectDepartments(boolean lock);

    List<Role> selectRoles(boolean lock);

    List<Delegation> selectDelegations(boolean lock);

    List<User> selectUsers(List<Long> ids, boolean lock);

    List<User> selectAllUsers();

    int insertAppointment(long userId, int level, long deptId, long actorId);

    int reactivate(long userId, int level, long deptId, long actorId);

    int revoke(long userId);

    int deleteDelegations(long userId);

    int deleteDelegation(long userId, long roleId);

    int insertDelegation(long userId, long roleId, long actorId);

    int advanceRevision(long userId, int expected);

}
