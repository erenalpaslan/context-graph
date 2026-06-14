package com.example

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNull
import org.mockito.Mockito.mock

class UserServiceTest {
    private val repo = mock(com.example.repository.UserRepository::class.java)
    private val service = UserService(repo)

    @Test
    fun `findById returns null for missing user`() {
        assertNull(service.findById(999L))
    }
}
