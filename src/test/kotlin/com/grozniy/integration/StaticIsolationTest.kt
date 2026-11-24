package com.grozniy.integration

import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import com.grozniy.integration.TransparentIsolationTest.Companion.launch
import com.grozniy.integration.TransparentIsolationTest.Companion.assertSuccess

class StaticIsolationTest {
    @Test
    fun javaAndKotlinStaticsAreIndependentAcrossTestsAndUnmocking() {
        State.barrier = CyclicBarrier(2)
        State.finished = CountDownLatch(1)
        val listener = object : TestExecutionListener {
            override fun executionFinished(id: TestIdentifier, result: TestExecutionResult) {
                if (id.isTest && id.displayName == "first()") State.finished.countDown()
            }
        }
        assertSuccess(launch(FirstFixture::class.java, SecondFixture::class.java, parallel = true, listener = listener), 2)
        assertEquals("java-real", JavaStaticApi.value())
        assertEquals("kotlin-real", staticGreeting())
    }

    @Test
    fun staticClearAndClearAllOnlyAffectTheCurrentTest() {
        State.barrier = CyclicBarrier(2)
        assertSuccess(launch(ClearFixture::class.java, parallel = true), 2)
        assertEquals("java-real", JavaStaticApi.value())
    }

    @Test
    fun nestedLauncherRestoresTheParentsStaticMock() {
        assertSuccess(launch(OuterFixture::class.java), 1)
        assertEquals("java-real", JavaStaticApi.value())
    }

    @Test
    fun failedTestWithoutMockKExtensionStillReleasesStaticProxy() {
        val summary = launch(FailureFixture::class.java)
        assertEquals(1, summary.testsFailedCount)
        assertEquals("static failure", summary.failures.single().exception.message)
        assertEquals("java-real", JavaStaticApi.value())
    }

    @Test
    fun constructorFailureStillRemovesStaticInstrumentation() {
        val summary = launch(ConstructorFailureFixture::class.java)
        assertEquals(1, summary.testsFailedCount)
        assertEquals("java-real", JavaStaticApi.value())
    }

    class ConstructorFailureFixture {
        init {
            mockkStatic(JavaStaticApi::class)
            every { JavaStaticApi.value() } returns "constructor"
            error("static constructor failure")
        }
        @Test fun never() { fail<Unit>("must not run") }
    }

    @Test
    fun mockkStaticBlockAndRepeatedRegistrationAreSupported() {
        assertSuccess(launch(BlockFixture::class.java), 1)
        assertEquals("java-real", JavaStaticApi.value())
    }

    @Test
    fun unsupportedObjectAndConstructorMocksFailBeforeChangingGlobalState() {
        assertSuccess(launch(UnsupportedFixture::class.java), 1)
    }

    @Test
    fun preexistingUnscopedStaticMockIsPreservedAndCannotBeOverwritten() {
        mockkStatic(JavaStaticApi::class)
        try {
            every { JavaStaticApi.value() } returns "outside"
            assertSuccess(launch(ConflictFixture::class.java), 1)
            assertEquals("outside", JavaStaticApi.value())
        } finally { unmockkStatic(JavaStaticApi::class) }
    }

    object State { lateinit var barrier: CyclicBarrier; lateinit var finished: CountDownLatch }

    @ExtendWith(MockKExtension::class)
    @MockKExtension.ConfirmVerification
    @MockKExtension.CheckUnnecessaryStub
    class FirstFixture {
        @Test fun first() {
            mockkStatic(JavaStaticApi::class)
            mockkStatic(::staticGreeting)
            every { JavaStaticApi.value() } returns "java-first"
            every { staticGreeting() } returns "kotlin-first"
            State.barrier.await(20, TimeUnit.SECONDS)
            assertEquals("java-first", JavaStaticApi.value())
            assertEquals("kotlin-first", staticGreeting())
            verify(exactly = 1) { JavaStaticApi.value(); staticGreeting() }
            unmockkStatic(JavaStaticApi::class)
            assertEquals("java-real", JavaStaticApi.value())
        }
    }

    @ExtendWith(MockKExtension::class)
    class SecondFixture {
        @Test fun second() {
            mockkStatic(JavaStaticApi::class)
            mockkStatic(::staticGreeting)
            every { JavaStaticApi.value() } returns "java-second"
            every { staticGreeting() } returns "kotlin-second"
            assertEquals("java-second", JavaStaticApi.value())
            assertEquals("kotlin-second", staticGreeting())
            State.barrier.await(20, TimeUnit.SECONDS)
            assertEquals("java-real", CompletableFuture.supplyAsync { JavaStaticApi.value() }.get(10, TimeUnit.SECONDS))
            assertTrue(State.finished.await(20, TimeUnit.SECONDS))
            assertEquals("java-second", JavaStaticApi.value())
            assertEquals("kotlin-second", staticGreeting())
            verify(exactly = 2) { JavaStaticApi.value(); staticGreeting() }
        }
    }

    @ExtendWith(MockKExtension::class)
    @Execution(ExecutionMode.CONCURRENT)
    class ClearFixture {
        @Test fun clear() {
            mockkStatic(JavaStaticApi::class)
            every { JavaStaticApi.value() } returns "mine"
            State.barrier.await(20, TimeUnit.SECONDS)
            clearStaticMockk(JavaStaticApi::class)
            assertEquals("java-real", JavaStaticApi.value())
            every { JavaStaticApi.value() } returns "mine-again"
            clearAllMocks()
            assertEquals("java-real", JavaStaticApi.value())
            State.barrier.await(20, TimeUnit.SECONDS)
        }
        @Test fun keep() {
            mockkStatic(JavaStaticApi::class)
            every { JavaStaticApi.value() } returns "kept"
            JavaStaticApi.value()
            State.barrier.await(20, TimeUnit.SECONDS)
            State.barrier.await(20, TimeUnit.SECONDS)
            assertEquals("kept", JavaStaticApi.value())
            verify(exactly = 2) { JavaStaticApi.value() }
        }
    }

    @ExtendWith(MockKExtension::class)
    class OuterFixture {
        @Test fun outer() {
            mockkStatic(JavaStaticApi::class)
            every { JavaStaticApi.value() } returns "outer"
            assertSuccess(launch(InnerFixture::class.java), 1)
            assertEquals("outer", JavaStaticApi.value())
        }
    }
    @ExtendWith(MockKExtension::class)
    class InnerFixture {
        @Test fun inner() {
            mockkStatic(JavaStaticApi::class)
            every { JavaStaticApi.value() } returns "inner"
            assertEquals("inner", JavaStaticApi.value())
            unmockkAll()
            assertEquals("java-real", JavaStaticApi.value())
        }
    }
    class FailureFixture {
        @Test fun fail() {
            mockkStatic(JavaStaticApi::class)
            every { JavaStaticApi.value() } returns "failed"
            assertEquals("failed", JavaStaticApi.value())
            error("static failure")
        }
    }
    @ExtendWith(MockKExtension::class)
    class BlockFixture {
        @Test fun blocks() {
            mockkStatic(JavaStaticApi::class) {
                every { JavaStaticApi.value() } returns "block"
                assertEquals("block", JavaStaticApi.value())
            }
            assertEquals("java-real", JavaStaticApi.value())
            mockkStatic(JavaStaticApi::class)
            every { JavaStaticApi.value() } returns "first"
            mockkStatic(JavaStaticApi::class)
            assertEquals("java-real", JavaStaticApi.value())
            every { JavaStaticApi.value() } returns "second"
            assertEquals("second", JavaStaticApi.value())
            unmockkAll()
            assertEquals("java-real", JavaStaticApi.value())
        }
    }
    class UnsupportedFixture {
        @Test fun unsupported() {
            assertThrows(IllegalStateException::class.java) { mockkObject(State) }
            assertThrows(IllegalStateException::class.java) { mockkConstructor(Client::class) }
        }
    }
    class ConflictFixture {
        @Test fun conflict() {
            assertThrows(IllegalStateException::class.java) { mockkStatic(JavaStaticApi::class) }
        }
    }
}
