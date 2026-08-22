**Exploration: authenticate user**

Found 11 symbols across 2 files.

**Blast radius — what depends on these (update/verify before editing)**

- `authenticate` (src/main/kotlin/com/example/UserService.kt:16) — 1 caller; tests: `src/main/kotlin/com/example/AuthController.kt`
- `findById` (src/main/kotlin/com/example/UserService.kt:7) — 1 caller; tests: `src/test/kotlin/com/example/UserServiceTest.kt`

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`src/main/kotlin/com/example/UserService.kt`** — authenticate(method), UserService(class), findById(method), create(method), delete(method), +2 more

```kotlin
1	package com.example
2	
3	import com.example.model.User
4	import com.example.repository.UserRepository
5	
6	class UserService(private val repo: UserRepository) {
7	    fun findById(id: Long): User? = repo.findById(id)
8	
9	    fun create(name: String, email: String): User {
10	        val user = User(id = 0, name = name, email = email)
11	        return repo.save(user)
12	    }
13	
14	    fun delete(id: Long): Boolean = repo.delete(id)
15	
16	    fun authenticate(email: String, password: String): Boolean {
17	        val user = repo.findByEmail(email) ?: return false
18	        return user.passwordHash == hash(password)
19	    }
20	
21	    private fun hash(input: String): String = input.reversed()
22	}
```

**`src/main/kotlin/com/example/AuthController.kt`** — login(method), AuthController(class), register(method), com.example(namespace)

```kotlin
1	package com.example
2	
3	class AuthController(private val userService: UserService) {
4	    fun login(email: String, password: String): String {
5	        if (!userService.authenticate(email, password)) error("Invalid credentials")
6	        return "token-${email.hashCode()}"
7	    }
8	
9	    fun register(name: String, email: String, password: String): Long {
10	        val user = userService.create(name, email)
11	        return user.id
12	    }
13	}
```

