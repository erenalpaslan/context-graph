package com.example

class AuthController(private val userService: UserService) {
    fun login(email: String, password: String): String {
        if (!userService.authenticate(email, password)) error("Invalid credentials")
        return "token-${email.hashCode()}"
    }

    fun register(name: String, email: String, password: String): Long {
        val user = userService.create(name, email)
        return user.id
    }
}
