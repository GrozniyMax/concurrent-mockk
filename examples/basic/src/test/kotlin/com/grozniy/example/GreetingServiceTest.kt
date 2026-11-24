package com.grozniy.example

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

interface UserRepository { fun name(id: Long): String }
fun greeting(name: String): String = "Hello, " + name + "!"

class GreetingService(private val repository: UserRepository) {
    fun greet(id: Long): String = greeting(repository.name(id))
}

@ExtendWith(MockKExtension::class)
class GreetingServiceTest {
    @MockK private lateinit var repository: UserRepository
    private lateinit var service: GreetingService

    @BeforeEach
    fun setup() {
        service = GreetingService(repository)
        mockkStatic(::greeting)
    }

    @Test
    fun greetsAlice() {
        every { repository.name(1L) } returns "Alice"
        every { greeting("Alice") } returns "Hi, Alice!"
        assertEquals("Hi, Alice!", service.greet(1L))
        verify(exactly = 1) { repository.name(1L); greeting("Alice") }
    }

    @Test
    fun greetsBob() {
        every { repository.name(1L) } returns "Bob"
        every { greeting("Bob") } returns "Welcome, Bob!"
        assertEquals("Welcome, Bob!", service.greet(1L))
        verify(exactly = 1) { repository.name(1L); greeting("Bob") }
    }
}
