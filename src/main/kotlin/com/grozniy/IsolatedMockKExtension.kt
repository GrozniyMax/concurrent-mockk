package com.grozniy

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.lang.reflect.Method

/** Creates a fresh context per test invocation, independent of worker thread reuse. */
class IsolatedMockKExtension : BeforeEachCallback, AfterEachCallback, ParameterResolver {
    override fun beforeEach(context: ExtensionContext) {
        val mocks = store(context).getOrComputeIfAbsent(KEY, { MockContext() }, MockContext::class.java)
        store(context).put(BINDING, ActiveMockContext.bind(mocks))
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type == MockContext::class.java &&
            parameterContext.declaringExecutable is Method && extensionContext.testMethod.isPresent

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): MockContext =
        store(extensionContext).get(KEY, MockContext::class.java)

    override fun afterEach(context: ExtensionContext) {
        val mocks = store(context).remove(KEY, MockContext::class.java) ?: return
        try {
            mocks.close()
        } catch (error: Throwable) {
            val original = context.executionException.orElse(null)
            if (original == null) throw error
            if (original !== error) original.addSuppressed(error)
        } finally {
            store(context).remove(BINDING, AutoCloseable::class.java)?.close()
        }
    }

    private fun store(context: ExtensionContext) = context.getStore(NAMESPACE)

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(IsolatedMockKExtension::class.java)
        const val KEY = "mock-context"
        const val BINDING = "thread-binding"
    }
}
