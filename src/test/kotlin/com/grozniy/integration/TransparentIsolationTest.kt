package com.grozniy.integration

import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.SpyK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.junit5.MockKExtension
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
import org.junit.platform.launcher.listeners.TestExecutionSummary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

interface Repository { fun find(): String; fun child(): Child }
interface Child { fun value(): String }
class Client(val repository: Repository) { fun find() = repository.find() }
class RealRepository : Repository {
    override fun find() = "real"
    override fun child(): Child = error("not used")
}

class TransparentIsolationTest {
    @Test
    fun parallelClassesKeepPlainFieldsAnnotationsAndParametersAfterNeighborCleanup() {
        ParallelState.barrier = CyclicBarrier(2)
        ParallelState.finished = CountDownLatch(1)
        ParallelState.snapshots.clear()
        val outside = mockk<Repository> { every { find() } returns "outside" }
        val listener = object : TestExecutionListener {
            override fun executionFinished(id: TestIdentifier, result: TestExecutionResult) {
                if (id.isTest && id.displayName == "first(Repository)") ParallelState.finished.countDown()
            }
        }
        val summary = launch(FirstFixture::class.java, SecondFixture::class.java, parallel = true, listener = listener)
        assertSuccess(summary, 2)
        assertNotEquals(ParallelState.firstThread, ParallelState.secondThread)
        assertEquals("outside", outside.find())
        ParallelState.snapshots.forEach { mock ->
            verify(exactly = 0) { mock.find() }
            assertThrows(MockKException::class.java) { mock.find() }
        }
        verify(exactly = 0) { FirstFixture.spyReference.find() }
        assertEquals("real", FirstFixture.spyReference.find())
        clearMocks(outside)
    }

    @Test
    fun explicitClearAllOnlyAffectsTheCurrentTest() {
        ClearState.barrier = CyclicBarrier(2)
        assertSuccess(launch(ClearFixture::class.java, parallel = true), 2)
    }

    @Test
    fun repeatedTestsReuseAThreadButGetFreshMocks() {
        RepeatedFixture.mocks.clear()
        RepeatedFixture.threads.clear()
        assertSuccess(launch(RepeatedFixture::class.java), 3)
        assertEquals(1, RepeatedFixture.threads.toSet().size)
        assertEquals(3, RepeatedFixture.mocks.size)
        RepeatedFixture.mocks.forEach { assertThrows(MockKException::class.java) { it.find() } }
    }

    @Test
    fun constructorFailureDoesNotLeakScopeOrConfiguredAnswers() {
        val summary = launch(ConstructorFailureFixture::class.java)
        assertEquals(1, summary.testsFailedCount)
        assertThrows(MockKException::class.java) { ConstructorFailureFixture.captured.find() }
        assertSuccess(launch(PlainFixture::class.java), 1)
        val outside = mockk<Repository> { every { find() } returns "still works" }
        assertEquals("still works", outside.find())
        clearMocks(outside)
    }

    @Test
    fun setupAndBodyFailuresStillCleanOwnedMocks() {
        FailureState.mocks.clear()
        val summary = launch(SetupFailureFixture::class.java, BodyFailureFixture::class.java)
        assertEquals(2, summary.testsFailedCount)
        assertEquals(setOf("setup failure", "body failure"), summary.failures.map { it.exception.message }.toSet())
        FailureState.mocks.forEach { mock ->
            verify(exactly = 0) { mock.find() }
            assertThrows(MockKException::class.java) { mock.find() }
        }
    }

    @Test
    fun relaxedChildrenAreAlsoCleaned() {
        assertSuccess(launch(RelaxedFixture::class.java), 1)
        verify(exactly = 0) { RelaxedFixture.child.value() }
        assertEquals("", RelaxedFixture.child.value())
    }

    @Test
    fun nestedLauncherRestoresTheOuterScope() {
        assertSuccess(launch(NestedLauncherFixture::class.java), 1)
    }

    @Test
    fun globalVerificationAndStubChecksOnlySeeTheCurrentTest() {
        VerificationState.ready = CountDownLatch(1)
        VerificationState.finished = CountDownLatch(1)
        val listener = object : TestExecutionListener {
            override fun executionFinished(id: TestIdentifier, result: TestExecutionResult) {
                if (id.isTest && id.displayName == "checked()") VerificationState.finished.countDown()
            }
        }
        assertSuccess(launch(CheckedFixture::class.java, UncheckedFixture::class.java, parallel = true, listener = listener), 2)
    }

    @Test
    fun standardConfirmVerificationStillReportsOwnUnverifiedCalls() {
        val summary = launch(UnverifiedFixture::class.java)
        assertEquals(1, summary.testsFailedCount)
        assertTrue(summary.failures.single().exception.message.orEmpty().contains("Verification acknowledgment failed"))
    }

    @Test
    fun perClassIsRejectedBeforeCreatingSharedMocks() {
        val summary = launch(PerClassFixture::class.java)
        assertTrue(summary.totalFailureCount > 0)
        assertTrue(summary.failures.any { it.exception.message.orEmpty().contains("PER_METHOD") })
    }

    @Test
    fun dynamicFactoriesAreRejectedBeforeCreatingSharedContexts() {
        val summary = launch(DynamicFixture::class.java)
        assertTrue(summary.totalFailureCount > 0)
        assertTrue(summary.failures.any { it.exception.message.orEmpty().contains("@TestFactory") })
    }

    class DynamicFixture {
        @TestFactory fun tests() = listOf(DynamicTest.dynamicTest("dynamic") { })
    }

    @Test
    fun nestedJupiterTestsHaveIndependentScopes() {
        assertSuccess(launch(NestedFixture::class.java), 1)
    }

    @Test
    fun parallelConstructorInjectionUsesEachTestsOwnParameterCache() {
        InjectionState.barrier = CyclicBarrier(2)
        assertSuccess(launch(InjectionFixture::class.java, parallel = true), 2)
    }

    object InjectionState { lateinit var barrier: CyclicBarrier }
    class Gate
    class CacheBarrierResolver : org.junit.jupiter.api.extension.ParameterResolver {
        override fun supportsParameter(p: org.junit.jupiter.api.extension.ParameterContext, c: org.junit.jupiter.api.extension.ExtensionContext) =
            p.parameter.type == Gate::class.java
        override fun resolveParameter(p: org.junit.jupiter.api.extension.ParameterContext, c: org.junit.jupiter.api.extension.ExtensionContext): Any {
            InjectionState.barrier.await(20, TimeUnit.SECONDS)
            return Gate()
        }
    }
    @ExtendWith(MockKExtension::class, CacheBarrierResolver::class)
    @Execution(ExecutionMode.CONCURRENT)
    class InjectionFixture {
        @Test fun first(@MockK own: Repository, gate: Gate, @InjectMockKs client: Client) {
            assertSame(own, client.repository)
        }
        @Test fun second(@MockK own: Repository, gate: Gate, @InjectMockKs client: Client) {
            assertSame(own, client.repository)
        }
    }

    companion object {
        fun launch(vararg classes: Class<*>, parallel: Boolean = false, listener: TestExecutionListener? = null): TestExecutionSummary {
            val summary = SummaryGeneratingListener()
            val request = LauncherDiscoveryRequestBuilder.request()
                .selectors(classes.map { selectClass(it) })
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.enabled", parallel.toString())
                .configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .build()
            LauncherFactory.create().execute(request, *listOfNotNull(summary, listener).toTypedArray())
            return summary.summary
        }

        fun assertSuccess(summary: TestExecutionSummary, count: Long) {
            assertEquals(count, summary.testsSucceededCount, summary.failures.joinToString { it.exception.stackTraceToString() })
            assertEquals(0, summary.totalFailureCount)
        }
    }

    object ParallelState {
        lateinit var barrier: CyclicBarrier
        lateinit var finished: CountDownLatch
        val snapshots = CopyOnWriteArrayList<Repository>()
        var firstThread = 0L
        var secondThread = 0L
    }

    @ExtendWith(MockKExtension::class)
    class FirstFixture {
        private val field = mockk<Repository>()
        @MockK lateinit var annotated: Repository
        @RelaxedMockK lateinit var relaxed: Repository
        @InjectMockKs lateinit var client: Client
        @SpyK var spy = RealRepository()

        @Test fun first(@MockK parameter: Repository) {
            spyReference = spy
            every { spy.find() } returns "spy"
            assertEquals("spy", spy.find())
            verify(exactly = 1) { spy.find() }
            ParallelState.firstThread = Thread.currentThread().id
            every { field.find() } returns "first-field"
            every { annotated.find() } returns "first-annotation"
            every { parameter.find() } returns "first-parameter"
            assertEquals("", relaxed.find())
            ParallelState.snapshots.addAll(listOf(field, annotated, parameter))
            ParallelState.barrier.await(20, TimeUnit.SECONDS)
            assertEquals("first-field", field.find())
            assertEquals("first-annotation", client.find())
            assertEquals("first-parameter", parameter.find())
        }
        companion object { lateinit var spyReference: RealRepository }
    }

    @ExtendWith(MockKExtension::class)
    class SecondFixture(@MockK private val constructorMock: Repository) {
        private val field = mockk<Repository>()
        @MockK lateinit var annotated: Repository
        @Test fun second() {
            ParallelState.secondThread = Thread.currentThread().id
            every { field.find() } returns "second-field"
            every { annotated.find() } returns "second-annotation"
            every { constructorMock.find() } returns "second-constructor"
            field.find()
            ParallelState.barrier.await(20, TimeUnit.SECONDS)
            assertTrue(ParallelState.finished.await(20, TimeUnit.SECONDS))
            assertEquals("second-field", field.find())
            assertEquals("second-annotation", annotated.find())
            assertEquals("second-constructor", constructorMock.find())
            verify(exactly = 2) { field.find() }
        }
    }

    object ClearState { lateinit var barrier: CyclicBarrier }
    @ExtendWith(MockKExtension::class)
    @Execution(ExecutionMode.CONCURRENT)
    class ClearFixture {
        @Test fun clear() {
            val mine = mockk<Repository> { every { find() } returns "mine" }
            ClearState.barrier.await(20, TimeUnit.SECONDS)
            clearAllMocks()
            assertThrows(MockKException::class.java) { mine.find() }
            ClearState.barrier.await(20, TimeUnit.SECONDS)
        }
        @Test fun keep() {
            val mine = mockk<Repository> { every { find() } returns "kept" }
            mine.find()
            ClearState.barrier.await(20, TimeUnit.SECONDS)
            ClearState.barrier.await(20, TimeUnit.SECONDS)
            assertEquals("kept", mine.find())
            verify(exactly = 2) { mine.find() }
        }
    }

    @ExtendWith(MockKExtension::class)
    class RepeatedFixture {
        private val repository = mockk<Repository>()
        @RepeatedTest(3) fun repeat() {
            mocks.forEach { assertThrows(MockKException::class.java) { it.find() } }
            every { repository.find() } returns "fresh"
            assertEquals("fresh", repository.find())
            mocks.add(repository)
            threads.add(Thread.currentThread().id)
        }
        companion object { val mocks = mutableListOf<Repository>(); val threads = mutableListOf<Long>() }
    }

    class ConstructorFailureFixture {
        init {
            captured = mockk { every { find() } returns "constructor" }
            error("constructor failure")
        }
        @Test fun never() { fail<Unit>("must not run") }
        companion object { lateinit var captured: Repository }
    }

    class PlainFixture {
        @Test fun test() {
            val mine = mockk<Repository> { every { find() } returns "plain" }
            assertEquals("plain", mine.find())
        }
    }

    object FailureState { val mocks = mutableListOf<Repository>() }
    @ExtendWith(MockKExtension::class)
    class SetupFailureFixture {
        @MockK lateinit var repository: Repository
        @BeforeEach fun setup() {
            FailureState.mocks.add(repository)
            every { repository.find() } returns "setup"
            repository.find()
            error("setup failure")
        }
        @Test fun never() { fail<Unit>("must not run") }
    }
    @ExtendWith(MockKExtension::class)
    class BodyFailureFixture {
        @Test fun fail() {
            val mine = mockk<Repository> { every { find() } returns "body" }
            FailureState.mocks.add(mine)
            mine.find()
            error("body failure")
        }
    }

    @ExtendWith(MockKExtension::class)
    class RelaxedFixture {
        @Test fun test() {
            val root = mockk<Repository>(relaxed = true)
            child = root.child()
            every { child.value() } returns "configured child"
            assertEquals("configured child", child.value())
        }
        companion object { lateinit var child: Child }
    }

    @ExtendWith(MockKExtension::class)
    class NestedLauncherFixture {
        @Test fun nested() {
            val mine = mockk<Repository> { every { find() } returns "outer" }
            assertSuccess(launch(PlainFixture::class.java), 1)
            assertEquals("outer", mine.find())
            clearAllMocks()
            assertThrows(MockKException::class.java) { mine.find() }
        }
    }

    object VerificationState { lateinit var ready: CountDownLatch; lateinit var finished: CountDownLatch }
    @ExtendWith(MockKExtension::class)
    @MockKExtension.ConfirmVerification
    @MockKExtension.CheckUnnecessaryStub
    class CheckedFixture {
        @Test fun checked() {
            val mine = mockk<Repository> { every { find() } returns "checked" }
            assertTrue(VerificationState.ready.await(20, TimeUnit.SECONDS))
            mine.find()
            verify(exactly = 1) { mine.find() }
        }
    }
    @ExtendWith(MockKExtension::class)
    class UncheckedFixture {
        @Test fun unchecked() {
            val mine = mockk<Repository> { every { find() } returns "unused" }
            val unverified = mockk<Repository>(relaxed = true)
            unverified.find()
            VerificationState.ready.countDown()
            assertTrue(VerificationState.finished.await(20, TimeUnit.SECONDS))
            assertEquals("unused", mine.find())
        }
    }
    @ExtendWith(MockKExtension::class)
    @MockKExtension.ConfirmVerification
    class UnverifiedFixture {
        @Test fun test() { mockk<Repository>(relaxed = true).find() }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PerClassFixture { @Test fun test() {} }

    @ExtendWith(MockKExtension::class)
    class NestedFixture {
        private val outer = mockk<Repository>()
        @Nested inner class Inner {
            @MockK lateinit var annotated: Repository
            @Test fun test() {
                every { outer.find() } returns "outer"
                every { annotated.find() } returns "inner"
                assertEquals("outer", outer.find())
                assertEquals("inner", annotated.find())
            }
        }
    }
}
