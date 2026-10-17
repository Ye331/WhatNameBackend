package com.whatname.iam.service.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import java.util.List;
import java.util.Map;

/** 在调用方事务中读取的不可变事实快照，不跨请求缓存。 */
public record AdminState(Map<Long, Appointment> appointments, Map<Long, Department> departments,
                         Map<Long, User> users, Map<Long, Role> roles, List<Delegation> delegations) {
    public AdminState {
        appointments = Map.copyOf(appointments);
        departments = Map.copyOf(departments);
        users = Map.copyOf(users);
        roles = Map.copyOf(roles);
        delegations = List.copyOf(delegations);
    }
}
