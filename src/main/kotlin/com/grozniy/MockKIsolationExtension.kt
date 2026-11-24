package com.grozniy

import com.grozniy.internal.MockKIsolation
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionConfigurationException
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstanceFactoryContext
import org.junit.jupiter.api.extension.TestInstancePreConstructCallback
import org.junit.jupiter.api.extension.TestInstantiationAwareExtension.ExtensionContextScope

/** Auto-detected integration for ordinary MockK imports and MockKExtension. */
class MockKIsolationExtension : TestInstancePreConstructCallback, BeforeEachCallback, AfterEachCallback {
    override fun getTestInstantiationExtensionContextScope(rootContext: ExtensionContext) = ExtensionContextScope.TEST_METHOD

    override fun preConstructTestInstance(factoryContext: TestInstanceFactoryContext, context: ExtensionContext) {
        if (context.testInstanceLifecycle.orElse(TestInstance.Lifecycle.PER_METHOD) == TestInstance.Lifecycle.PER_CLASS) {
            throw ExtensionConfigurationException("MockK isolation requires PER_METHOD: shared test instances cannot own per-test mocks")
        }
        if (context.testMethod.map { it.isAnnotationPresent(TestFactory::class.java) }.orElse(false)) {
            throw ExtensionConfigurationException("MockK isolation does not support @TestFactory dynamic tests")
        }
        start(context)
    }

    override fun beforeEach(context: ExtensionContext) = start(context)

    private fun start(context: ExtensionContext) {
        val store = context.getStore(NAMESPACE)
        val key = context.uniqueId
        if (store.get(key) == null) store.put(key, MockKIsolation.open())
    }

    override fun afterEach(context: ExtensionContext) {
        val scope = context.getStore(NAMESPACE).remove(context.uniqueId, MockKIsolation.Scope::class.java) ?: return
        try {
            scope.close()
        } catch (error: Throwable) {
            val original = context.executionException.orElse(null)
            if (original == null) throw error
            if (original !== error) original.addSuppressed(error)
        }
    }

    private companion object {
        val NAMESPACE = ExtensionContext.Namespace.create(MockKIsolationExtension::class.java)
    }
}
