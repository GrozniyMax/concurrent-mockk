package com.grozniy.internal

import io.mockk.MockKCancellationRegistry
import io.mockk.impl.instantiation.JvmMockFactoryHelper
import io.mockk.impl.instantiation.JvmStaticMockFactory
import io.mockk.impl.stub.MockType
import io.mockk.impl.stub.SpyKStub
import io.mockk.impl.stub.Stub
import io.mockk.impl.stub.StubRepository
import io.mockk.proxy.Cancelable
import io.mockk.proxy.MockKInvocationHandler
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/** One JVM proxy per class; dispatch and cancellation belong to individual tests. */
object StaticMockIsolation {
    private val monitor = Any()
    private val proxies = IdentityHashMap<Class<*>, SharedProxy>()
    private class SharedProxy(val cancellation: Cancelable<Class<*>>, var references: Int)

    @JvmStatic
    fun create(factory: JvmStaticMockFactory, type: KClass<*>): (() -> Unit)? {
        val scope = MockKIsolation.currentScope()
        if (scope == null) {
            synchronized(monitor) {
                check(!proxies.containsKey(type.java)) { "Cannot mix unscoped and isolated static mocks for the same class" }
            }
            return null
        }
        return scope.statics.create(factory, type)
    }

    private fun acquire(factory: JvmStaticMockFactory, type: KClass<*>) = synchronized(monitor) {
        val existing = proxies[type.java]
        if (existing != null) {
            existing.references++
        } else {
            synchronized(factory.refCntMap.counter) {
                check((factory.refCntMap.counter[type] ?: 0) <= 0) {
                    "A static mock created outside isolated tests is already active for " + type
                }
            }
            val dispatcher = object : MockKInvocationHandler {
                override fun invocation(self: Any, method: java.lang.reflect.Method?, originalCall: java.util.concurrent.Callable<*>?, args: Array<Any?>): Any? {
                    val entry = MockKIsolation.currentScope()?.statics?.find(factory.stubRepository, type.java)
                    return if (entry == null) originalCall?.call()
                    else entry.handler.invocation(self, method, originalCall, args)
                }
            }
            proxies[type.java] = SharedProxy(factory.proxyMaker.staticProxy(type.java, dispatcher), 1)
        }
    }

    private fun release(type: Class<*>) = synchronized(monitor) {
        val proxy = proxies[type] ?: return@synchronized
        if (--proxy.references == 0) {
            try { proxy.cancellation.cancel() } finally { proxies.remove(type) }
        }
    }

    internal class Entry(val stub: SpyKStub<*>, val handler: MockKInvocationHandler, var references: Int = 0)

    internal class StaticScope(private val owner: MockKIsolation.Scope) {
        private val live = IdentityHashMap<StubRepository, MutableMap<Class<*>, Entry>>()
        private val history = IdentityHashMap<StubRepository, MutableList<Stub>>()
        private val cancellations = mutableListOf<() -> Unit>()
        private val registries = mutableMapOf<MockKCancellationRegistry.Type, MockKCancellationRegistry.RegistryPerType>()

        fun registry(type: MockKCancellationRegistry.Type) = registries.getOrPut(type) { MockKCancellationRegistry.RegistryPerType() }
        fun find(repository: StubRepository, type: Any): Entry? = live[repository]?.get(type)
        fun stubs(repository: StubRepository): List<Stub> = history[repository].orEmpty()

        fun create(factory: JvmStaticMockFactory, type: KClass<*>): () -> Unit {
            val entries = live.getOrPut(factory.stubRepository) { IdentityHashMap() }
            var entry = entries[type.java]
            if (entry == null) {
                val stub = SpyKStub(type, "isolated static " + type.simpleName, factory.gatewayAccess, true, MockType.STATIC)
                stub.hashCodeStr = Integer.toHexString(System.identityHashCode(type.java))
                entry = Entry(stub, JvmMockFactoryHelper.mockHandler(stub))
                acquire(factory, type)
                entries[type.java] = entry
                history.getOrPut(factory.stubRepository) { mutableListOf() }.add(stub)
                owner.record(factory.stubRepository, stub)
            }
            val captured = entry
            captured.references++
            val cancelled = AtomicBoolean()
            val cancellation = {
                if (cancelled.compareAndSet(false, true) && --captured.references == 0) {
                    entries.remove(type.java)
                    release(type.java)
                }
                Unit
            }
            cancellations.add(cancellation)
            return cancellation
        }

        fun cancelAll() {
            var failure: Throwable? = null
            val operations = registries.values.map { registry -> { registry.cancelAll() } } + cancellations.toList()
            for (cancel in operations) {
                try { cancel() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                }
            }
            cancellations.clear()
            failure?.let { throw it }
        }

        fun dropReferences() { live.clear(); history.clear(); cancellations.clear(); registries.clear() }
    }
}
