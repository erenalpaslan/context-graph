package com.example.auth;

import java.util.List;
import java.util.Objects;

public class UserService {
    private final String name;
    private int loginCount, failureCount;

    public UserService(String name) {
        this.name = name;
    }

    public String save(String user) {
        return user;
    }

    public String save(String user, int retries) {
        return user;
    }

    class AuditLog {
        void record() {
        }
    }
}
