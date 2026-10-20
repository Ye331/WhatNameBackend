package com.whatname.iam.service.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import com.whatname.iam.mapper.admin.AdminMapper;
import java.util.*;

/** 为服务测试提供内存数据替身；真实事务行为由 AdminIT 使用 MySQL 验证。 */
public class AdminTestFixture implements AdminMapper {
    public final Map<Long, Appointment> appointments = new TreeMap<>();
    public final Map<Long, Department> departments = new TreeMap<>();
    public final Map<Long, User> users = new TreeMap<>();
    public final Map<Long, Role> roles = new TreeMap<>();
    public final List<Delegation> delegations = new ArrayList<>();
    public final List<AdminPorts.Change> events = new ArrayList<>();
    public long actorId = 1;
    public AdminPorts.Functions functions = (actor, permission) -> {};
    public AdminPorts.Integrity integrity = new AdminPorts.Integrity() {
        public void verify(AdminState state) {}
        public void sign(AdminState state, Set<Long> changed) {}
    };

    public AdminTestFixture() {
        departments.put(10L, new Department(10L, 0L, 1));
        departments.put(20L, new Department(20L, 10L, 1));
        departments.put(21L, new Department(21L, 20L, 1));
        departments.put(22L, new Department(22L, 21L, 1));
        departments.put(30L, new Department(30L, 10L, 1));
        appointments.put(1L, new Appointment(1L, 1, 10L, 1, 1, null));
        appointments.put(2L, new Appointment(2L, 2, 20L, 1, 1, 1L));
        appointments.put(3L, new Appointment(3L, 3, 21L, 1, 1, 2L));
        appointments.put(4L, new Appointment(4L, 4, 22L, 1, 1, 3L));
        appointments.put(5L, new Appointment(5L, 2, 30L, 1, 1, 1L));
        users.put(1L, new User(1L, 10L, 1));
        users.put(2L, new User(2L, 20L, 1));
        users.put(3L, new User(3L, 21L, 1));
        users.put(4L, new User(4L, 22L, 1));
        users.put(5L, new User(5L, 30L, 1));
        users.put(6L, new User(6L, 21L, 1));
        for (long id : List.of(100L, 101L)) roles.put(id, new Role(id, 1, 1));
        roles.put(200L, new Role(200L, 2, 1));
        roles.put(300L, new Role(300L, 3, 1));
        delegations.addAll(List.of(new Delegation(2L, 100L, 1L), new Delegation(2L, 101L, 1L),
                new Delegation(3L, 100L, 2L), new Delegation(3L, 101L, 1L),
                new Delegation(4L, 100L, 3L), new Delegation(5L, 100L, 1L)));
    }
    public AdminService service() {
        return new AdminService(this, new AdminRules(), () -> actorId,
                (id, permission) -> functions.require(id, permission), new AdminPorts.Integrity() {
                    public void verify(AdminState state) { integrity.verify(state); }
                    public void sign(AdminState state, Set<Long> changed) { integrity.sign(state, changed); }
                }, events::add, (actor, role) -> false);
    }
    public List<Appointment> selectAppointments(boolean lock) { return List.copyOf(appointments.values()); }
    public List<Department> selectDepartments(boolean lock) { return List.copyOf(departments.values()); }
    public List<Role> selectRoles(boolean lock) { return List.copyOf(roles.values()); }
    public List<Delegation> selectDelegations(boolean lock) { return List.copyOf(delegations); }
    public List<User> selectUsers(List<Long> ids, boolean lock) { return ids.stream().map(users::get).filter(Objects::nonNull).toList(); }
    public List<User> selectAllUsers() { return List.copyOf(users.values()); }
    public int insertAppointment(long id, int level, long deptId, long actor) {
        if (appointments.containsKey(id)) return 0;
        appointments.put(id, new Appointment(id, level, deptId, 1, 1, actor)); return 1;
    }
    public int reactivate(long id, int level, long deptId, long actor) {
        var old = appointments.get(id);
        if (old == null || old.status() != 0) return 0;
        appointments.put(id, new Appointment(id, level, deptId, 1, old.delegableRevision(), actor)); return 1;
    }
    public int revoke(long id) {
        var old = appointments.get(id);
        if (old == null || old.status() != 1) return 0;
        appointments.put(id, new Appointment(id, old.adminLevel(), old.deptId(), 0, old.delegableRevision(), old.appointedBy())); return 1;
    }
    public int deleteDelegations(long id) {
        int old = delegations.size(); delegations.removeIf(d -> d.adminUserId() == id); return old - delegations.size();
    }
    public int deleteDelegation(long id, long role) {
        return delegations.removeIf(d -> d.adminUserId() == id && d.roleId() == role) ? 1 : 0;
    }
    public int insertDelegation(long id, long role, long actor) { delegations.add(new Delegation(id, role, actor)); return 1; }
    public int advanceRevision(long id, int expected) {
        var old = appointments.get(id);
        if (old == null || old.delegableRevision() != expected || expected == Integer.MAX_VALUE) return 0;
        appointments.put(id, new Appointment(id, old.adminLevel(), old.deptId(), old.status(), expected + 1, old.appointedBy())); return 1;
    }
}
