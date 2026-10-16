package com.whatname.iam.service.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import java.util.List;
import java.util.Map;

/** Immutable facts read in the caller's transaction; never cached across requests. */
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
