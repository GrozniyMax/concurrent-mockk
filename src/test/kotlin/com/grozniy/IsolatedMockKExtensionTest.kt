package com.grozniy

import io.mockk.MockKException
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class IsolatedMockKExtensionTest {
    @Test
    fun `parallel invocations overlap and cleanup cannot affect an active test`() {
        ParallelFixture.barrier = CyclicBarrier(2)
        ParallelFixture.finished = CountDownLatch(1)
        val listener = object : TestExecutionListener {
            override fun executionFinished(id: TestIdentifier, result: TestExecutionResult) {
                if (id.isTest && id.displayName == "first()") ParallelFixture.finished.countDown()
            }
        }
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
        val summary = launch(ParallelFixture::class.java, true, listener)
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
        assertEquals(2, summary.testsSucceededCount, summary.failures.toString())
        assertNotEquals(ParallelFixture.firstThread, ParallelFixture.secondThread)
        assertNotSame(ParallelFixture.firstContext, ParallelFixture.secondContext)
        assertThrows(IllegalStateException::class.java) { ParallelFixture.firstContext.mockk<Service>() }
        assertThrows(IllegalStateException::class.java) { ParallelFixture.secondContext.mockk<Service>() }
    }

    @Test
    fun `sequential tests on the same thread get fresh contexts`() {
        SequentialFixture.contexts.clear()
        SequentialFixture.threads.clear()
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
        val summary = launch(SequentialFixture::class.java)
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
        assertEquals(2, summary.testsSucceededCount)
        assertEquals(SequentialFixture.threads[0], SequentialFixture.threads[1])
        assertNotSame(SequentialFixture.contexts[0], SequentialFixture.contexts[1])
        SequentialFixture.contexts.forEach { context ->
            assertThrows(IllegalStateException::class.java) { context.mockk<Service>() }
        }
    }

    @Test
    fun `failing test and configuration both get cleaned up`() {
        FailureFixture.mocks.clear()
        val summary = launch(FailureFixture::class.java)
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
        assertEquals(2, summary.testsFailedCount)
        assertEquals(setOf("body failure", "configuration failure"), summary.failures.map { it.exception.message }.toSet())
        FailureFixture.mocks.forEach { mock ->
            verify(exactly = 0) { mock.value() }
            assertThrows(MockKException::class.java) { mock.value() }
        }
    }

    @Test
    fun `cleanup failure is suppressed on original test failure`() {
        val summary = launch(CleanupFailureFixture::class.java)
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
        assertEquals(1, summary.testsFailedCount)
        val error = summary.failures.single().exception
        assertEquals("original failure", error.message)
        assertEquals(1, error.suppressed.size)
        assertInstanceOf(MockKException::class.java, error.suppressed.single())
    }

    @Test
    fun `before and after each share the implicit context across repetitions`() {
        LifecycleFixture.mocks.clear()
        val summary = launch(LifecycleFixture::class.java)
        assertEquals(2, summary.testsSucceededCount, summary.failures.toString())
        assertEquals(2, LifecycleFixture.mocks.size)
        assertNotSame(LifecycleFixture.mocks[0], LifecycleFixture.mocks[1])
        LifecycleFixture.mocks.forEach { service ->
            verify(exactly = 0) { service.value() }
            assertThrows(MockKException::class.java) { service.value() }
        }
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
    }

    @Test
    fun `before each failure still cleans mocks and removes the binding`() {
        val summary = launch(SetupFailureFixture::class.java)
        assertEquals(1, summary.testsFailedCount)
        assertEquals("setup failure", summary.failures.single().exception.message)
        verify(exactly = 0) { SetupFailureFixture.service.value() }
        assertThrows(MockKException::class.java) { SetupFailureFixture.service.value() }
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
    }

    @Test
    fun `nested execution restores the outer test binding`() {
        val summary = launch(NestedLauncherFixture::class.java)
        assertEquals(1, summary.testsSucceededCount, summary.failures.toString())
        assertThrows(IllegalStateException::class.java) { mockk<Service>() }
    }

    @ExtendWith(IsolatedMockKExtension::class)
    class LifecycleFixture {
        private lateinit var service: Service
        @BeforeEach fun setup() {
            service = mockk { every { value() } returns "setup" }
            mocks.add(service)
        }
        @RepeatedTest(2) fun test() {
            assertEquals("setup", service.value())
        }
        @AfterEach fun teardown() {
            verify(exactly = 1) { service.value() }
            mockk<Service>(relaxUnitFun = true).notify("teardown")
        }
        companion object { val mocks = mutableListOf<Service>() }
    }

    @ExtendWith(IsolatedMockKExtension::class)
    class SetupFailureFixture {
        @BeforeEach fun setup() {
            service = mockk { every { value() } returns "setup" }
            service.value()
            error("setup failure")
        }
        @Test fun test() { fail<Unit>("Test must not run") }
        companion object { lateinit var service: Service }
    }

    @ExtendWith(IsolatedMockKExtension::class)
    class NestedLauncherFixture {
        @Test fun test() {
            val outer = ActiveMockContext.current()
            val summary = IsolatedMockKExtensionTest().launch(LifecycleFixture::class.java)
            assertEquals(2, summary.testsSucceededCount)
            assertSame(outer, ActiveMockContext.current())
            assertEquals("", mockk<Service>(relaxed = true).value())
        }
    }

    private fun launch(type: Class<*>, parallel: Boolean = false, listener: TestExecutionListener? = null) =
        SummaryGeneratingListener().also { summary ->
            val request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(type))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", parallel.toString())
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .build()
            LauncherFactory.create().execute(request, *listOfNotNull(summary, listener).toTypedArray())
        }.summary

    @ExtendWith(IsolatedMockKExtension::class)
    @Execution(ExecutionMode.CONCURRENT)
    class ParallelFixture {
        @Test
        fun first() {
            firstContext = ActiveMockContext.current()
            firstThread = Thread.currentThread().id
            val service = mockk<Service> { every { value() } returns "first" }
            barrier.await(20, TimeUnit.SECONDS)
            assertEquals("first", service.value())
            verify(exactly = 1) { service.value() }
        }

        @Test
        fun second() {
            secondContext = ActiveMockContext.current()
            secondThread = Thread.currentThread().id
            val service = mockk<Service> { every { value() } returns "second" }
            service.value()
            barrier.await(20, TimeUnit.SECONDS)
            assertTrue(finished.await(20, TimeUnit.SECONDS), "First test must finish, including cleanup")
            assertThrows(IllegalStateException::class.java) { firstContext.mockk<Service>() }
            assertEquals("second", service.value())
            verify(exactly = 2) { service.value() }
        }

        companion object {
            lateinit var barrier: CyclicBarrier
            lateinit var finished: CountDownLatch
            lateinit var firstContext: MockContext
            lateinit var secondContext: MockContext
            var firstThread = 0L
            var secondThread = 0L
        }
    }

    @ExtendWith(IsolatedMockKExtension::class)
    class SequentialFixture {
        @Test fun first() = record()
        @Test fun second() = record()

        private fun record() {
            val context = ActiveMockContext.current()
            contexts.forEach { previous ->
                assertThrows(IllegalStateException::class.java) { previous.mockk<Service>() }
            }
            contexts.add(context)
            threads.add(Thread.currentThread().id)
            assertEquals("", mockk<Service>(relaxed = true).value())
        }

        companion object {
            val contexts = mutableListOf<MockContext>()
            val threads = mutableListOf<Long>()
        }
    }

    @ExtendWith(IsolatedMockKExtension::class)
    class FailureFixture {
        @Test fun body() {
            val service = mockk<Service> { every { value() } returns "answer" }
            mocks.add(service)
            service.value()
            error("body failure")
        }

        @Test fun configuration() {
            mockk<Service> {
                mocks.add(this)
                every { value() } returns "answer"
                value()
                error("configuration failure")
            }
        }

        companion object { val mocks = mutableListOf<Service>() }
    }

    @ExtendWith(IsolatedMockKExtension::class)
    class CleanupFailureFixture {
        @Test fun failure(context: MockContext) {
            context.createMock({ Any() }, {})
            error("original failure")
        }
    }
}
