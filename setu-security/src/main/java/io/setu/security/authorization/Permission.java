package io.setu.security.authorization;

public enum Permission {

    TOPIC_CREATE,
    TOPIC_DELETE,

    PRODUCE,
    CONSUME,

    CONSUMER_GROUP_CREATE,
    CONSUMER_GROUP_READ,
    CONSUMER_GROUP_WRITE,
    VIEW_METADATA,

    ACL_READ,
    ACL_WRITE
}
