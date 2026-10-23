package com.whatname.iam.service.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import com.whatname.iam.repository.admin.AdminRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class AdminStateLoader {
    private final AdminRepository repository;

    AdminStateLoader(AdminRepository repository) {
        this.repository = repository;
    }

    AdminState load(boolean lock, long... userIds) {
        return load(lock, false, userIds);
    }

    AdminState loadOrganization() {
        return load(true, true);
    }

    private AdminState load(boolean lock, boolean allUsers, long... userIds) {
        // 将管理员相关写入串行化，包括委派清单的递归变更。
        // 使用当前读和固定加锁顺序，避免并发写入时依赖可重复读事务中的旧快照。
        var appointments = index(repository.selectAppointments(lock), Appointment::userId);
        var departments = index(repository.selectDepartments(lock), Department::id);
        var roles = index(repository.selectRoles(lock), Role::id);
        var delegations = repository.selectDelegations(lock);
        var ids = Arrays.stream(userIds).distinct().sorted().boxed().toList();
        var users =
                index(allUsers ? repository.selectAllUsers() : repository.selectUsers(ids, lock), User::id);
        return new AdminState(appointments, departments, users, roles, delegations);
    }

    private static <T> Map<Long, T> index(List<T> values, Function<T, Long> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
