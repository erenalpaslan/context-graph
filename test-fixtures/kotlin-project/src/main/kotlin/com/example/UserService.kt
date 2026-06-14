package com.example

import com.example.model.User
import com.example.repository.UserRepository

class UserService(private val repo: UserRepository) {
    fun findById(id: Long): User? = repo.findById(id)

    fun create(name: String, email: String): User {
        val user = User(id = 0, name = name, email = email)
        return repo.save(user)
    }

    fun delete(id: Long): Boolean = repo.delete(id)

    fun authenticate(email: String, password: String): Boolean {
        val user = repo.findByEmail(email) ?: return false
        return user.passwordHash == hash(password)
    }

    private fun hash(input: String): String = input.reversed()
}
