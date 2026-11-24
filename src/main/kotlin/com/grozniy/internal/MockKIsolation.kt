package com.grozniy.internal

import io.mockk.MockKCancellationRegistry
import io.mockk.impl.instantiation.JvmStaticMockFactory
import io.mockk.impl.instantiation.JvmObjectMockFactory
import io.mockk.impl.instantiation.JvmConstructorMockFactory
import io.mockk.MockKGateway.ClearOptions
import io.mockk.junit5.MockKExtension
import kotlin.reflect.KClass
import java.lang.instrument.ClassDefinition
import net.bytebuddy.asm.MemberSubstitution
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.matcher.ElementMatchers.any
import io.mockk.impl.stub.Stub
import io.mockk.impl.stub.StubRepository
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.asm.Advice
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.Collections
import java.util.IdentityHashMap

/** Internal bridge called by instrumented MockK bytecode. Not a user API. */
object MockKIsolation {
    private val active = ThreadLocal<Scope>()
    private var installed = false
    private val originalCache = MockKExtension::class.java.getDeclaredField("cache").apply { isAccessible = true }

    @Synchronized
    internal fun install() {
        if (installed) return
        val type = StubRepository::class.java
        check(type.getDeclaredMethod("add", Any::class.java, Stub::class.java).returnType == Void.TYPE)
        check(type.getDeclaredMethod("getAllStubs").returnType == List::class.java)
        val instrumentation = ByteBuddyAgent.install()
        val repositoryDefinition = ByteBuddy().redefine(type)
            .visit(Advice.to(StubRepositoryAdvice.Registration::class.java).on(named<MethodDescription>("add").and(takesArguments(2))))
            .visit(Advice.to(StubRepositoryAdvice.Lookup::class.java).on(named<MethodDescription>("get").and(takesArguments(1))))
            .visit(Advice.to(StubRepositoryAdvice.Enumeration::class.java).on(named<MethodDescription>("getAllStubs").and(takesArguments(0))))
            .make()
        check(originalCache.type == Map::class.java)
        val extensionDefinition = ByteBuddy().redefine(MockKExtension::class.java)
            .visit(MemberSubstitution.strict()
                .field(named<FieldDescription>("cache")).onRead()
                .replaceWith(MockKIsolation::class.java.getMethod("parameterCache", MockKExtension::class.java))
                .on(any<MethodDescription>()))
            .make()
        val staticDefinition = ByteBuddy().redefine(JvmStaticMockFactory::class.java)
            .visit(Advice.to(StaticMockAdvice.Creation::class.java).on(named<MethodDescription>("staticMockk")))
            .make()
        val registryDefinition = ByteBuddy().redefine(MockKCancellationRegistry::class.java)
            .visit(Advice.to(StaticMockAdvice.Registry::class.java).on(named<MethodDescription>("subRegistry")))
            .visit(Advice.to(StaticMockAdvice.Cancellation::class.java).on(named<MethodDescription>("cancelAll")))
            .make()
        val objectDefinition = ByteBuddy().redefine(JvmObjectMockFactory::class.java)
            .visit(Advice.to(StaticMockAdvice.UnsupportedGlobalMock::class.java).on(named<MethodDescription>("objectMockk")))
            .make()
        val constructorDefinition = ByteBuddy().redefine(JvmConstructorMockFactory::class.java)
            .visit(Advice.to(StaticMockAdvice.UnsupportedGlobalMock::class.java).on(named<MethodDescription>("constructorMockk")))
            .make()
        instrumentation.redefineClasses(
            ClassDefinition(type, repositoryDefinition.bytes),
            ClassDefinition(MockKExtension::class.java, extensionDefinition.bytes),
            ClassDefinition(JvmStaticMockFactory::class.java, staticDefinition.bytes),
            ClassDefinition(MockKCancellationRegistry::class.java, registryDefinition.bytes),
            ClassDefinition(JvmObjectMockFactory::class.java, objectDefinition.bytes),
            ClassDefinition(JvmConstructorMockFactory::class.java, constructorDefinition.bytes),
        )
        installed = true
    }

    internal fun open(): Scope {
        install()
        return Scope(active.get()).also { active.set(it) }
    }

    internal fun currentScope(): Scope? = active.get()

    @JvmStatic
    fun lookup(repository: StubRepository, mock: Any, original: Stub?): Stub? =
        active.get()?.statics?.find(repository, mock)?.stub ?: original

    @JvmStatic
    fun cancellationRegistry(type: MockKCancellationRegistry.Type, original: MockKCancellationRegistry.RegistryPerType): MockKCancellationRegistry.RegistryPerType =
        active.get()?.statics?.registry(type) ?: original

    @JvmStatic
    fun cancelAllStatics(): Boolean {
        val scope = active.get() ?: return false
        scope.statics.cancelAll()
        return true
    }

    @JvmStatic
    fun rejectUnsupportedGlobalMock() {
        check(active.get() == null) { "Object and constructor mocks are not supported by MockK test isolation" }
    }

    @JvmStatic
    fun record(repository: StubRepository, stub: Stub) {
        active.get()?.record(repository, stub)
    }

    @JvmStatic
    fun filter(repository: StubRepository, stubs: List<Stub>): List<Stub> =
        active.get()?.filter(repository, stubs) ?: stubs

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun parameterCache(extension: MockKExtension): MutableMap<KClass<out Any>, Any> =
        active.get()?.parameterCache(extension)
            ?: originalCache.get(extension) as MutableMap<KClass<out Any>, Any>

    internal class Scope(private val previous: Scope?) : ExtensionContext.Store.CloseableResource {
        private val owner = Thread.currentThread()
        val statics = StaticMockIsolation.StaticScope(this)
        private val owned = IdentityHashMap<StubRepository, MutableSet<Stub>>()
        private var closed = false
        private val caches = IdentityHashMap<MockKExtension, MutableMap<KClass<out Any>, Any>>()

        @Synchronized
        fun parameterCache(extension: MockKExtension): MutableMap<KClass<out Any>, Any> =
            caches.getOrPut(extension) { mutableMapOf() }

        @Synchronized
        fun record(repository: StubRepository, stub: Stub) {
            check(!closed) { "MockK isolation scope is closed" }
            owned.getOrPut(repository) { Collections.newSetFromMap(IdentityHashMap()) }.add(stub)
        }

        @Synchronized
        fun filter(repository: StubRepository, stubs: List<Stub>): List<Stub> =
            stubs.filter { owned[repository]?.contains(it) == true } + statics.stubs(repository)

        @Synchronized
        override fun close() {
            if (closed) return
            check(Thread.currentThread() === owner) { "MockK isolation requires lifecycle callbacks on the test thread" }
            closed = true
            var failure: Throwable? = null
            try {
                try { statics.cancelAll() } catch (error: Throwable) { failure = error }
                for (stub in owned.values.flatMap { it.toList() }) {
                    try {
                        stub.clear(ClearOptions(true, true, true, true, true))
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                    }
                }
            } finally {
                owned.clear()
                caches.clear()
                statics.dropReferences()
                if (previous == null) active.remove() else active.set(previous)
            }
            failure?.let { throw it }
        }
    }
}
