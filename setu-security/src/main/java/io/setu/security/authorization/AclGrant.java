package io.setu.security.authorization;

record AclGrant(String principal, String resource, Permission permission) { }
