package io.setu.security.authorization;

public interface AccessPolicy {
    boolean allows(String principal, String resource, Permission permission);
}