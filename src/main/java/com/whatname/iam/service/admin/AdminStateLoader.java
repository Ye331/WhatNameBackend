package com.whatname.iam.service.admin;

import com.whatname.iam.mapper.admin.AdminMapper;
import com.whatname.iam.entity.admin.AdminData.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class AdminStateLoader {
    private final AdminMapper mapper;
    AdminStateLoader(AdminMapper mapper) { this.mapper = mapper; }

    AdminState load(boolean lock, long... userIds) {
        // Stage 1 deliberately serializes M5 writes, including recursive delegation changes.
        // Current reads and a fixed lock order avoid stale RR snapshots during competing writes.
        var appointments = index(mapper.selectAppointments(lock), Appointment::userId);
        var departments = index(mapper.selectDepartments(lock), Department::id);
        var roles = index(mapper.selectRoles(lock), Role::id);
        var delegations = mapper.selectDelegations(lock);
        var ids = Arrays.stream(userIds).distinct().sorted().boxed().toList();
        var users = index(mapper.selectUsers(ids, lock), User::id);
        return new AdminState(appointments, departments, users, roles, delegations);
    }

    private static <T> Map<Long, T> index(List<T> values, Function<T, Long> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
