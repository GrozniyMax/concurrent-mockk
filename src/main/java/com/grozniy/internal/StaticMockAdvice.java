package com.grozniy.internal;

import io.mockk.MockKCancellationRegistry;
import io.mockk.impl.instantiation.JvmStaticMockFactory;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.reflect.KClass;
import net.bytebuddy.asm.Advice;

public final class StaticMockAdvice {
    private StaticMockAdvice() {}

    public static class Creation {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Function0<Unit> enter(@Advice.This JvmStaticMockFactory factory, @Advice.Argument(0) KClass<?> type) {
            return StaticMockIsolation.create(factory, type);
        }
        @Advice.OnMethodExit
        public static void exit(@Advice.Enter Function0<Unit> scoped, @Advice.Return(readOnly = false) Function0<Unit> result) {
            if (scoped != null) result = scoped;
        }
    }

    public static class Registry {
        @Advice.OnMethodExit
        public static void exit(@Advice.Argument(0) MockKCancellationRegistry.Type type,
                @Advice.Return(readOnly = false) MockKCancellationRegistry.RegistryPerType result) {
            result = MockKIsolation.cancellationRegistry(type, result);
        }
    }

    public static class Cancellation {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter() { return MockKIsolation.cancelAllStatics(); }
    }

    public static class UnsupportedGlobalMock {
        @Advice.OnMethodEnter
        public static void enter() { MockKIsolation.rejectUnsupportedGlobalMock(); }
    }
}
