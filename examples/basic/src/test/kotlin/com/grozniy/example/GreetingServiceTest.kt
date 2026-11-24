package com.grozniy.example

import com.grozniy.IsolatedMockKExtension
import com.grozniy.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

interface UserRepository { fun name(id: Long): String }

class GreetingService(private val repository: UserRepository) {
    fun greet(id: Long): String = "Hello, " + repository.name(id) + "!"
}

@ExtendWith(IsolatedMockKExtension::class)
class GreetingServiceTest {
    private lateinit var repository: UserRepository
    private lateinit var service: GreetingService

    @BeforeEach
    fun setup() {
        repository = mockk()
        service = GreetingService(repository)
    }

    @Test
    fun greetsAlice() {
        every { repository.name(1L) } returns "Alice"
        assertEquals("Hello, Alice!", service.greet(1L))
        verify(exactly = 1) { repository.name(1L) }
    }

    @Test
    fun greetsBob() {
        every { repository.name(1L) } returns "Bob"
        assertEquals("Hello, Bob!", service.greet(1L))
        verify(exactly = 1) { repository.name(1L) }
    }
}
