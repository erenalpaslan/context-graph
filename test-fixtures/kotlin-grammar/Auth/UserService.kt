package com.example.auth

import kotlin.collections.List
import kotlin.math.*

class UserService(val name: String, private var loginCount: Int) {
    fun save(user: String): String {
        return user
    }

    fun save(user: String, retries: Int): String {
        return user
    }

    companion object {
        const val MAX_RETRIES = 3

        fun of(name: String): UserService = UserService(name, 0)
    }

    inner class AuditLog {
        fun record() {
        }
    }
}
