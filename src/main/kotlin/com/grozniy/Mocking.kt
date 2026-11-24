package com.grozniy

/** Creates a mock owned by the current JUnit test, with automatic cleanup. */
inline fun <reified T : Any> mockk(
    name: String? = null,
    relaxed: Boolean = false,
    relaxUnitFun: Boolean = false,
    crossinline block: T.() -> Unit = {},
): T = ActiveMockContext.current().mockk(name, relaxed, relaxUnitFun, block)

@PublishedApi
internal object ActiveMockContext {
    private val active = ThreadLocal<MockContext>()

    @PublishedApi
    internal fun current(): MockContext = checkNotNull(active.get()) {
        "No active MockContext. Register IsolatedMockKExtension and create mocks in the test or @BeforeEach on the test thread."
    }

    fun bind(context: MockContext): AutoCloseable {
        val previous = active.get()
        active.set(context)
        return AutoCloseable {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
