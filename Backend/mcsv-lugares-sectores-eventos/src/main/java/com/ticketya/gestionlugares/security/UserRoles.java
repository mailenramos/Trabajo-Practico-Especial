package com.ticketya.gestionlugares.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class UserRoles {
    private static final UserRoles EMPTY = new UserRoles(Set.of());

    private final Set<String> values;

    private UserRoles(Set<String> values) {
        this.values = values;
    }

    public static UserRoles fromHeader(String header) {
        if (header == null || header.isBlank()) {
            return EMPTY;
        }
        Set<String> roles = Arrays.stream(header.split(",", -1))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return roles.isEmpty() ? EMPTY : new UserRoles(roles);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public boolean contains(String role) {
        return role != null && values.contains(role.toUpperCase(Locale.ROOT));
    }

    public boolean containsAny(String... roles) {
        return Arrays.stream(roles).anyMatch(this::contains);
    }
}
