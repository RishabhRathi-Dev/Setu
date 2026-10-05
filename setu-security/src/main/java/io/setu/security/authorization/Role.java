package io.setu.security.authorization;

import java.util.EnumSet;
import java.util.Set;

public enum Role {

    PRODUCER(
            Permission.PRODUCE
    ),

    CONSUMER(
            Permission.CONSUME,
            Permission.CONSUMER_GROUP_READ,
            Permission.CONSUMER_GROUP_CREATE,
            Permission.CONSUMER_GROUP_WRITE,
            Permission.VIEW_METADATA
    ),

    OPERATOR(
            Permission.TOPIC_CREATE,
            Permission.TOPIC_DELETE,
            Permission.PRODUCE,
            Permission.CONSUME,
            Permission.CONSUMER_GROUP_CREATE,
            Permission.CONSUMER_GROUP_READ,
            Permission.CONSUMER_GROUP_WRITE,
            Permission.VIEW_METADATA
    ),

    ADMIN(
            Permission.values()
    );

    private final Set<Permission> permissions;

    Role(Permission... permissions) {
        this.permissions =
                permissions.length == Permission.values().length
                        ? EnumSet.allOf(Permission.class)
                        : EnumSet.copyOf(
                        Set.of(permissions)
                );
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }
}
