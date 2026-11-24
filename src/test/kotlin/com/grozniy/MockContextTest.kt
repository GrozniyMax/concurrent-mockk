package com.grozniy

import io.mockk.MockKException
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

interface Service {
    fun value(): String
    fun notify(message: String)
}

class MockContextTest {
    @Test
    fun `creates strict relaxed and unit-relaxed mocks`() {
        MockContext().use { context ->
            val strict = context.mockk<Service>(name = "strict") { every { value() } returns "answer" }
            assertEquals("answer", strict.value())
            verify(exactly = 1) { strict.value() }
            assertThrows(MockKException::class.java) { strict.notify("unexpected") }
            val relaxed = context.mockk<Service>(relaxed = true)
            assertEquals("", relaxed.value())
            relaxed.notify("ok")
            val unitRelaxed = context.mockk<Service>(relaxUnitFun = true)
            unitRelaxed.notify("ok")
            assertThrows(MockKException::class.java) { unitRelaxed.value() }
        }
    }

    @Test
    fun `close clears owned answers and calls but preserves another context`() {
        val first = MockContext()
        MockContext().use { second ->
            val a = first.mockk<Service> { every { value() } returns "first" }
            val b = second.mockk<Service> { every { value() } returns "second" }
            a.value()
            b.value()
            first.close()
            first.close()
            verify(exactly = 0) { a.value() }
            assertThrows(MockKException::class.java) { a.value() }
            assertEquals("second", b.value())
            verify(exactly = 2) { b.value() }
            assertThrows(IllegalStateException::class.java) { first.mockk<Service>() }
        }
    }

    @Test
    fun `configuration failure still registers mock for cleanup`() {
        val context = MockContext()
        lateinit var captured: Service
        val expected = IllegalArgumentException("configuration")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            context.mockk<Service> {
                captured = this
                every { value() } returns "configured"
                value()
                throw expected
            }
        }
        assertSame(expected, actual)
        context.close()
        verify(exactly = 0) { captured.value() }
        assertThrows(MockKException::class.java) { captured.value() }
    }

    @Test
    fun `cleanup attempts remaining mocks after a cleanup failure`() {
        val context = MockContext()
        // A non-mock deliberately triggers the public MockK cleanup failure path.
        context.createMock({ Any() }, {})
        val service = context.mockk<Service> { every { value() } returns "configured" }
        service.value()
        assertThrows(MockKException::class.java) { context.close() }
        verify(exactly = 0) { service.value() }
        assertThrows(MockKException::class.java) { service.value() }
        context.close()
        assertThrows(IllegalStateException::class.java) { context.mockk<Service>() }
    }
}
