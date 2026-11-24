package com.grozniy.internal;

import io.mockk.impl.stub.Stub;
import io.mockk.impl.stub.StubRepository;
import java.util.List;
import net.bytebuddy.asm.Advice;

/** Bytecode advice is inlined into MockK; bridge methods must stay public. */
public final class StubRepositoryAdvice {
    private StubRepositoryAdvice() {}

    public static class Registration {
        @Advice.OnMethodExit
        public static void exit(@Advice.This StubRepository repository, @Advice.Argument(1) Stub stub) {
            MockKIsolation.record(repository, stub);
        }
    }

    public static class Lookup {
        @Advice.OnMethodExit
        public static void exit(@Advice.This StubRepository repository, @Advice.Argument(0) Object mock,
                @Advice.Return(readOnly = false) Stub result) {
            result = MockKIsolation.lookup(repository, mock, result);
        }
    }

    public static class Enumeration {
        @Advice.OnMethodExit
        public static void exit(
                @Advice.This StubRepository repository,
                @Advice.Return(readOnly = false) List<Stub> stubs) {
            stubs = MockKIsolation.filter(repository, stubs);
        }
    }
}
