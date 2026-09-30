package io.setu.security.authorization;

public enum Permission {

    TOPIC_CREATE,
    TOPIC_DELETE,

    PRODUCE,
    CONSUME,

    CONSUMER_GROUP_CREATE,
    CONSUMER_GROUP_READ,

    ACL_READ,
    ACL_WRITE
}