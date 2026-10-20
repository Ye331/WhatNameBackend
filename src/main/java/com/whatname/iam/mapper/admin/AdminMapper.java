package com.whatname.iam.mapper.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface AdminMapper {
    List<Appointment> selectAppointments(@Param("lock") boolean lock);
    List<Department> selectDepartments(@Param("lock") boolean lock);
    List<Role> selectRoles(@Param("lock") boolean lock);
    List<Delegation> selectDelegations(@Param("lock") boolean lock);
    List<User> selectUsers(@Param("ids") List<Long> ids, @Param("lock") boolean lock);
    List<User> selectAllUsers();
    int insertAppointment(@Param("userId") long userId, @Param("level") int level,
                          @Param("deptId") long deptId, @Param("actorId") long actorId);
    int reactivate(@Param("userId") long userId, @Param("level") int level,
                   @Param("deptId") long deptId, @Param("actorId") long actorId);
    int revoke(@Param("userId") long userId);
    int deleteDelegations(@Param("userId") long userId);
    int deleteDelegation(@Param("userId") long userId, @Param("roleId") long roleId);
    int insertDelegation(@Param("userId") long userId, @Param("roleId") long roleId,
                         @Param("actorId") long actorId);
    int advanceRevision(@Param("userId") long userId, @Param("expected") int expected);
}
