package com.ticketya.gestionlugares.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserRolesTest {

    @Test
    void parsesAllCommaSeparatedRolesWithoutPrecedence() {
        UserRoles roles = UserRoles.fromHeader(" CLIENTE, ORGANIZADOR ,ADMIN ");

        assertTrue(roles.contains("CLIENTE"));
        assertTrue(roles.contains("ORGANIZADOR"));
        assertTrue(roles.contains("ADMIN"));
        assertFalse(roles.isEmpty());
    }

    @Test
    void ignoresEmptyEntriesAndNormalizesRoleCase() {
        UserRoles roles = UserRoles.fromHeader(" ,organizador,, ADMIN, ");

        assertTrue(roles.contains("ORGANIZADOR"));
        assertTrue(roles.contains("ADMIN"));
        assertFalse(roles.isEmpty());
    }

    @Test
    void missingOrBlankHeaderHasNoRoles() {
        assertTrue(UserRoles.fromHeader(null).isEmpty());
        assertTrue(UserRoles.fromHeader(" , ").isEmpty());
    }
}
