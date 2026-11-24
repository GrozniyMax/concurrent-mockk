package com.grozniy

import io.mockk.clearMocks

/** Owns the mocks of a single test. Use with [IsolatedMockKExtension]. */
class MockContext : AutoCloseable {
    private val monitor = Any()
    private val mocks = mutableListOf<Any>()
    private var closed = false

    /** Registers the mock before running [block], including when configuration fails. */
    inline fun <reified T : Any> mockk(
        name: String? = null,
        relaxed: Boolean = false,
        relaxUnitFun: Boolean = false,
        crossinline block: T.() -> Unit = {},
    ): T = createMock(
        factory = { io.mockk.mockk<T>(name = name, relaxed = relaxed, relaxUnitFun = relaxUnitFun) },
        configure = { it.block() },
    )

    @PublishedApi
    internal fun <T : Any> createMock(factory: () -> T, configure: (T) -> Unit): T = synchronized(monitor) {
        check(!closed) { "MockContext is closed" }
        val mock = factory()
        mocks.add(mock)
        configure(mock)
        mock
    }

    /** Clears only owned mocks, drops references, and tolerates repeated calls. */
    override fun close(): Unit = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        var failure: Throwable? = null
        try {
            for (mock in mocks) {
                try {
                    clearMocks(mock)
                } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                }
            }
        } finally {
            mocks.clear()
        }
        failure?.let { throw it }
    }
}
