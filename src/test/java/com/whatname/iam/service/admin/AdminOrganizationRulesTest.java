package com.whatname.iam.service.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AdminOrganizationRulesTest {
    final AdminTestFixture db = new AdminTestFixture();
    final AdminRules rules = new AdminRules();
    AdminState state() { return new AdminState(db.appointments, db.departments, db.users, db.roles, db.delegations); }

    @Test void movingSubtreeRevokesExpandingRootButPreservesShrinkingRootAndSubtreeRoot() {
        var before = state();
        db.departments.put(21L, new Department(21L, 30L, 1));
        assertThat(rules.organizationReview(before, state())).containsExactly(5L);
        assertThat(rules.contains(state(), db.appointments.get(2L), 21)).isFalse();
        assertThat(rules.contains(state(), db.appointments.get(3L), 22)).isTrue();
    }
    @Test void mergeNeverMapsSourceAppointmentToTargetOrPromotesLevel() {
        var before = state();
        db.departments.put(20L, new Department(20L, 10L, 1, 30L));
        db.departments.put(21L, new Department(21L, 30L, 1));
        db.users.put(2L, new User(2L, 30L, 1));
        assertThat(rules.organizationReview(before, state())).containsExactly(2L, 5L);
        assertThat(db.appointments.get(2L).deptId()).isEqualTo(20L);
        assertThat(db.appointments.get(2L).adminLevel()).isEqualTo(2);
    }
    @Test void movingAdministratorIntoChildRequiresReviewForBothMovedActorAndExpandedChild() {
        var before = state();
        db.users.put(2L, new User(2L, 21L, 1));
        assertThat(rules.organizationReview(before, state())).containsExactly(2L, 3L);
    }
    @Test void newDescendantDoesNotSilentlyExpandExistingDepartmentAppointment() {
        var before = state();
        db.departments.put(99L, new Department(99L, 20L, 1));
        assertThat(rules.organizationReview(before, state())).containsExactly(2L);
    }
    @Test void incomingPeopleFromOutsideScopeAlsoRequireReviewWithoutTreeGrowth() {
        var before = state();
        db.users.put(6L, new User(6L, 30L, 1));
        assertThat(rules.organizationReview(before, state())).containsExactly(5L);
    }
    @Test void renameEquivalentAndTemporaryFreezeDoNotPermanentlyRevokeAppointment() {
        var before = state();
        assertThat(rules.organizationReview(before, state())).isEmpty();
        db.departments.put(20L, new Department(20L, 10L, 0));
        assertThat(rules.organizationReview(before, state())).isEmpty();
    }
}
