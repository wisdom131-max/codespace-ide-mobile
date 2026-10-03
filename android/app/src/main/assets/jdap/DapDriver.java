import com.microsoft.java.debug.core.adapter.*;
import com.microsoft.java.debug.core.adapter.ProtocolServer;
import com.microsoft.java.debug.core.protocol.Types;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import io.reactivex.Observable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.*;

/**
 * F6-b: the CodeSpace JVM DAP driver — REAL providers, no Eclipse, no third
 * JVM. Source lookup parses package/class from the source file; evaluation
 * weaves expressions into a redefined JdapEvalHost via the container's own
 * javac. HCR and debug-console completions are HONEST stubs (unsupported,
 * see the Layer 1 documentation for the future JDT-LS route).
 */
public class DapDriver {
    private static final ConsoleHandler LOG_HANDLER = new ConsoleHandler();

    static {
        LOG_HANDLER.setLevel(Level.INFO);
        Logger root = Logger.getLogger("java-debug");
        root.setLevel(isVerbose() ? Level.FINEST : Level.INFO);
        root.addHandler(LOG_HANDLER);
        root.setUseParentHandlers(false);
    }

    private static boolean isVerbose() {
        return Arrays.asList(new String[]{"--verbose", "-v"}).toString().contains(
            String.join(",", System.getProperty("jdap.args", "")));
    }

    /** Evaluation provider backed by the javac engine. The engine is created
     *  lazily per attached VM because providers are registered before attach. */
    static class EvalProvider implements IEvaluationProvider {
        private volatile EvalEngine engine;

        private EvalEngine engineFor(ThreadReference thread) {
            EvalEngine e = engine;
            if (e == null) {
                synchronized (this) {
                    if (engine == null) {
                        engine = new EvalEngine(thread.virtualMachine());
                    }
                    e = engine;
                }
            }
            return e;
        }

        @Override
        public boolean isInEvaluation(ThreadReference thread) {
            EvalEngine e = engine;
            return e != null && e.isInEvaluation(thread);
        }

        @Override
        public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
            try {
                return engineFor(thread).evaluate(expression, thread, depth);
            } catch (RuntimeException ex) {
                return failedFuture("Evaluation failed: " + rootMessage(ex));
            }
        }

        @Override
        public CompletableFuture<Value> evaluate(String expression, ObjectReference receiver, ThreadReference thread) {
            return failedFuture("Object-level evaluation is not supported in F6-b; "
                + "use the frame context (received " + (receiver == null ? "null" : receiver.referenceType().name()) + ").");
        }

        @Override
        public CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint breakpoint, ThreadReference thread) {
            try {
                return engineFor(thread).evaluateForBreakpoint(breakpoint, thread);
            } catch (RuntimeException ex) {
                return failedFuture("Conditional breakpoint failed: " + rootMessage(ex));
            }
        }

        @Override
        public CompletableFuture<Value> invokeMethod(ObjectReference receiver, String methodName,
                String methodSignature, Value[] args, ThreadReference thread, boolean invokeSuper) {
            try {
                return engineFor(thread).invokeMethod(receiver, methodName, methodSignature, args, thread, invokeSuper);
            } catch (RuntimeException ex) {
                return failedFuture("Method invocation failed: " + rootMessage(ex));
            }
        }

        @Override
        public void clearState(ThreadReference thread) {
            EvalEngine e = engine;
            if (e != null) {
                e.clearState(thread);
            }
        }
    }

    static class HonestHCR implements IHotCodeReplaceProvider {
        @Override
        public void onClassRedefined(java.util.function.Consumer<List<String>> consumer) {
            // Hot code replace is not supported in F6-b. See LAYER-1 documentation.
        }

        @Override
        public CompletableFuture<List<String>> redefineClasses() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public Observable<HotCodeReplaceEvent> getEventHub() {
            return Observable.never();
        }
    }

    static class HonestCompletions implements ICompletionsProvider {
        @Override
        public List<Types.CompletionItem> codeComplete(com.sun.jdi.StackFrame frame, String code, int line, int column) {
            // Debug-console completions are not supported in F6-b (Layer 1 route documents them).
            return Collections.emptyList();
        }
    }

    static class RealVMManager implements IVirtualMachineManagerProvider {
        @Override
        public com.sun.jdi.VirtualMachineManager getVirtualMachineManager() {
            return Bootstrap.virtualMachineManager();
        }
    }

    private static ProviderContext buildContext() {
        ProviderContext context = new ProviderContext();
        context.registerProvider(IHotCodeReplaceProvider.class, new HonestHCR());
        context.registerProvider(IVirtualMachineManagerProvider.class, new RealVMManager());
        context.registerProvider(ISourceLookUpProvider.class, new SourceProvider());
        context.registerProvider(ICompletionsProvider.class, new HonestCompletions());
        context.registerProvider(IEvaluationProvider.class, new EvalProvider());
        return context;
    }

    public static void main(String[] args) throws Exception {
        List<String> argv = Arrays.asList(args);
        if (argv.contains("--selftest")) {
            ProviderContext ctx = buildContext();
            boolean javac = new java.io.File(
                System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "javac").canExecute();
            System.out.println("jdap-selftest OK java=" + System.getProperty("java.version")
                + " driver=DapDriver core=com.microsoft.java.debug.core-0.53.1"
                + " providers:source=real,eval=" + (javac ? "javac" : "MISSING-JAVAC") + ",hcr=stub(honest),completions=stub(honest)"
                + " context=" + (ctx.getProvider(ISourceLookUpProvider.class) != null ? "built" : "FAILED"));
            return;
        }
        ProtocolServer server = new ProtocolServer(System.in, System.out, buildContext());
        server.run();
    }

    private static CompletableFuture<Value> failedFuture(String message) {
        CompletableFuture<Value> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalStateException(message));
        return f;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 6 && cur.getCause() != null; i++) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }
}
