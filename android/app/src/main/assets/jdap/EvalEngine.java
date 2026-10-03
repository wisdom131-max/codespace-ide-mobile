import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.sun.jdi.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * F6-b: javac-based expression evaluation — no Eclipse, no JDT. Expressions
 * are woven into the BODY of a pre-declared eval slot of JdapEvalHost and
 * redefined in the target VM (this target's JVMTI can only change method
 * bodies, not add/delete methods, so slots are fixed). The slot is then
 * invoked on the paused thread with the frame's `this` and locals.
 * A fast JDI path handles the most common conditional-breakpoint shapes
 * (local vs literal) without compiling.
 *
 * Honest limitations (documented, not hidden):
 * - The debuggee must run with jdap-evalhost.jar on its classpath.
 * - Debuggee must be compiled with local-variable debug info (-g).
 * - Unqualified static members only resolve via import static when the frame
 *   class is public and in a named package; qualify otherwise.
 * - Frames with more than 7 visible locals / array locals are not supported.
 * - First evaluation of an expression+shape compiles with javac (~1-3s);
 *   repeat evaluations reuse the slot.
 */
public class EvalEngine {
    private static final Pattern FAST = Pattern.compile(
        "^\\s*([A-Za-z_$][\\w$]*)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+)\\s*$");
    private static final Pattern FAST_R = Pattern.compile(
        "^\\s*(-?\\d+)\\s*(>=|<=|==|!=|>|<)\\s*([A-Za-z_$][\\w$]*)\\s*$");
    static final String SLOT_SIG = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
        + "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
        + "Ljava/lang/Object;)Ljava/lang/Object;";
    private static final int SLOT_COUNT = 6;
    private static final int PARAM_COUNT = 8;
    private static final String SLOT_STUB =
        "        throw new UnsupportedOperationException(\"jdap eval slot K is empty\");";

    private final VirtualMachine vm;
    private final Set<Long> evaluatingThreads = ConcurrentHashMap.newKeySet();
    private final Map<String, CompiledEval> cache = new ConcurrentHashMap<>();
    private final File scratch = new File("/tmp/jdap-eval-" + ProcessHandle.current().pid());
    private final boolean[] slotBusy = new boolean[SLOT_COUNT];
    private String targetClasspath; // lazily read from the target VM
    private boolean hostLoaded = false;

    EvalEngine(VirtualMachine vm) {
        this.vm = vm;
    }

    boolean isInEvaluation(ThreadReference thread) {
        return evaluatingThreads.contains(thread.uniqueID());
    }

    void clearState(ThreadReference thread) {
        evaluatingThreads.remove(thread.uniqueID());
    }

    CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Value fast = tryFastPath(expression, thread.frame(depth));
                if (fast != null) {
                    return fast;
                }
                CompiledEval compiled = compileWithRetry(expression, thread, depth);
                return invokeCompiled(thread, compiled, depth);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, ForkJoinPool.commonPool());
    }

    /** JDI frame mirrors go stale whenever the suspend generation bumps (any
     *  invoke on the thread does this). Never hold a frame across calls —
     *  refetch at each use and retry once on a stale frame. */
    private CompiledEval compileWithRetry(String expression, ThreadReference thread, int depth) throws Exception {
        try {
            return compileFor(expression, thread.frame(depth), thread);
        } catch (InvalidStackFrameException e) {
            if (!thread.isSuspended()) {
                throw new IllegalStateException("Thread was resumed during evaluation; "
                    + "cannot evaluate a running thread.");
            }
            Thread.sleep(300); // let a concurrent suspend/invoke cycle settle
            return compileFor(expression, thread.frame(depth), thread);
        }
    }

    CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint bp, ThreadReference thread) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Value fast = tryFastPath(bp.getCondition(), thread.frame(0));
                if (fast != null) {
                    return fast;
                }
                CompiledEval compiled = (CompiledEval) bp.getCompiledConditionalExpression();
                if (compiled == null) {
                    compiled = compileWithRetry(bp.getCondition(), thread, 0);
                    bp.setCompiledConditionalExpression(compiled);
                }
                return invokeCompiled(thread, compiled, 0);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, ForkJoinPool.commonPool());
    }

    CompletableFuture<Value> invokeMethod(ObjectReference receiver, String methodName,
            String methodSignature, Value[] args, ThreadReference thread, boolean invokeSuper) {
        return CompletableFuture.supplyAsync(() -> {
            evaluatingThreads.add(thread.uniqueID());
            try {
                Method m = findMethod(receiver.referenceType(), methodName, methodSignature);
                if (m == null) {
                    throw new IllegalStateException("No method " + methodName + methodSignature
                        + " on " + receiver.referenceType().name());
                }
                return receiver.invokeMethod(thread, m, Arrays.asList(args), ObjectReference.INVOKE_SINGLE_THREADED);
            } catch (Exception e) {
                throw new CompletionException(e);
            } finally {
                evaluatingThreads.remove(thread.uniqueID());
            }
        }, ForkJoinPool.commonPool());
    }

    private Method findMethod(ReferenceType type, String name, String sig) {
        for (Method m : type.methodsByName(name)) {
            if (sig == null || sig.equals(m.signature()) || sig.equals(m.genericSignature())) {
                return m;
            }
        }
        return null;
    }

    /** Fast path: `local <op> intLiteral` (both orders) — no javac, pure JDI. */
    private Value tryFastPath(String expression, StackFrame frame) {
        if (expression == null) {
            return null;
        }
        Matcher m = FAST.matcher(expression);
        boolean reversed = false;
        if (!m.matches()) {
            m = FAST_R.matcher(expression);
            reversed = m.matches();
        }
        if (!m.matches()) {
            return null;
        }
        String localName;
        String op;
        long literal;
        if (reversed) {
            literal = Long.parseLong(m.group(1));
            op = flip(m.group(2));
            localName = m.group(3);
        } else {
            localName = m.group(1);
            op = m.group(2);
            literal = Long.parseLong(m.group(3));
        }
        LocalVariable var = null;
        try {
            for (LocalVariable candidate : frame.visibleVariables()) {
                if (candidate.name().equals(localName)) {
                    var = candidate;
                    break;
                }
            }
        } catch (AbsentInformationException e) {
            return null;
        }
        if (var == null) {
            return null;
        }
        Value raw = frame.getValue(var);
        long actual;
        if (raw instanceof IntegerValue) {
            actual = ((IntegerValue) raw).value();
        } else if (raw instanceof LongValue) {
            actual = ((LongValue) raw).value();
        } else {
            return null;
        }
        boolean result;
        switch (op) {
            case ">":  result = actual >  literal; break;
            case "<":  result = actual <  literal; break;
            case ">=": result = actual >= literal; break;
            case "<=": result = actual <= literal; break;
            case "==": result = actual == literal; break;
            case "!=": result = actual != literal; break;
            default: return null;
        }
        return vm.mirrorOf(result);
    }

    private static String flip(String op) {
        switch (op) {
            case ">": return "<";
            case "<": return ">";
            case ">=": return "<=";
            case "<=": return ">=";
            default: return op; // == and != are symmetric
        }
    }

    private synchronized CompiledEval compileFor(String expression, StackFrame frame,
            ThreadReference thread) throws Exception {
        String key = expression + "|" + frame.location().declaringType().name() + "|" + localsShape(frame);
        CompiledEval cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        int slot = freeSlot();
        if (slot < 0) {
            restorePristineHost();
            slot = freeSlot();
            if (slot < 0) {
                throw new IllegalStateException("No eval slot available; evaluation cache exhausted.");
            }
        }
        ensureHostLoaded(frame.location().declaringType().classLoader());
        ReferenceType declaring = frame.location().declaringType();

        List<LocalVariable> locals = new ArrayList<>();
        try {
            locals.addAll(frame.visibleVariables());
        } catch (AbsentInformationException e) {
            throw new IllegalStateException(
                "The debuggee was compiled without local-variable debug info (-g); evaluation needs it.");
        }
        if (locals.size() > PARAM_COUNT - 1) {
            throw new IllegalStateException("Frames with more than " + (PARAM_COUNT - 1)
                + " visible locals are not supported by the F6-b evaluator.");
        }
        for (LocalVariable v : locals) {
            if (v.typeName().startsWith("[")) {
                throw new IllegalStateException("Array local '" + v.name()
                    + "' is not supported by the F6-b evaluator.");
            }
        }

        StringBuilder body = new StringBuilder();
        ObjectReference self = frame.thisObject();
        if (self != null) {
            body.append("        ").append(declaring.name()).append(" __self = (")
                .append(declaring.name()).append(") a0;\n");
        }
        for (int i = 0; i < locals.size(); i++) {
            LocalVariable v = locals.get(i);
            String type = v.typeName();
            char param = (char) ('1' + i);
            body.append("        ").append(unboxStmt(type, v.name(), param));
        }

        StringBuilder src = new StringBuilder();
        if (declaring.isPublic() && declaring.name().contains(".")) {
            // import static only works for a PUBLIC class in a NAMED package —
            // javac cannot import from the unnamed package at all. For default-
            // package projects the host (also default package) can reference
            // members fully-qualified instead.
            src.append("import static ").append(declaring.name()).append(".*;\n");
        }
        src.append("public class JdapEvalHost {\n");
        src.append("    public static void ping() { }\n");
        for (int k = 0; k < SLOT_COUNT; k++) {
            src.append("    public static Object eval").append(k).append("(").append(paramDecl()).append(") throws Throwable {\n");
            if (k == slot) {
                src.append(body);
                src.append("        return __box(").append(rewriteThis(expression, self != null)).append(");\n");
            } else {
                src.append(SLOT_STUB.replace("K", String.valueOf(k))).append("\n");
            }
            src.append("    }\n");
        }
        appendBoxOverloads(src);
        src.append("}\n");

        compileHost(src.toString(), slot);
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (LocalVariable v : locals) {
            names.add(v.name());
            types.add(v.typeName());
        }
        CompiledEval compiled = new CompiledEval(slot, names, types, self != null, declaring.name());
        cache.put(key, compiled);
        slotBusy[slot] = true;
        return compiled;
    }

    private static String paramDecl() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PARAM_COUNT; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("Object a").append(i);
        }
        return sb.toString();
    }

    private static boolean isPrimitive(String typeName) {
        switch (typeName) {
            case "int": case "long": case "double": case "float":
            case "boolean": case "char": case "byte": case "short":
                return true;
            default:
                return false;
        }
    }

    private static String unboxStmt(String type, String name, char param) {
        switch (type) {
            case "int":     return "int " + name + " = ((Integer) a" + param + ").intValue();\n";
            case "long":    return "long " + name + " = ((Long) a" + param + ").longValue();\n";
            case "double":  return "double " + name + " = ((Double) a" + param + ").doubleValue();\n";
            case "float":   return "float " + name + " = ((Float) a" + param + ").floatValue();\n";
            case "boolean": return "boolean " + name + " = ((Boolean) a" + param + ").booleanValue();\n";
            case "char":    return "char " + name + " = ((Character) a" + param + ").charValue();\n";
            case "byte":    return "byte " + name + " = ((Byte) a" + param + ").byteValue();\n";
            case "short":   return "short " + name + " = ((Short) a" + param + ").shortValue();\n";
            default:        return type + " " + name + " = (" + type + ") a" + param + ";\n";
        }
    }

    private Value invokeCompiled(ThreadReference thread, CompiledEval compiled, int depth) throws Exception {
        ClassType host = (ClassType) vm.classesByName("JdapEvalHost").get(0);
        Method m = null;
        for (Method candidate : host.methodsByName("eval" + compiled.slot)) {
            if (SLOT_SIG.equals(candidate.signature())) {
                m = candidate;
                break;
            }
        }
        if (m == null) {
            throw new IllegalStateException("Eval slot " + compiled.slot + " vanished from the host.");
        }
        // Any JDI invoke (Class.forName, getProperty, boxing) invalidates frame
        // mirrors, so fetch a FRESH frame here and read every raw value BEFORE
        // the boxing invokes start. Value mirrors stay valid across invokes.
        StackFrame frame = thread.frame(depth);
        List<Value> raw = new ArrayList<>(PARAM_COUNT);
        raw.add(compiled.hasSelf ? frame.thisObject() : null);
        List<LocalVariable> visible = frame.visibleVariables();
        for (int i = 0; i < compiled.names.size(); i++) {
            LocalVariable match = null;
            for (LocalVariable v : visible) {
                if (v.name().equals(compiled.names.get(i))) {
                    match = v;
                    break;
                }
            }
            if (match == null) {
                throw new IllegalStateException("Local '" + compiled.names.get(i)
                    + "' is not visible in the current frame.");
            }
            raw.add(frame.getValue(match));
        }
        List<Value> args = new ArrayList<>(PARAM_COUNT);
        args.add(raw.get(0));
        for (int i = 0; i < compiled.names.size(); i++) {
            Value v = raw.get(i + 1);
            if (isPrimitive(compiled.types.get(i)) && v != null) {
                args.add(boxInTarget(v, compiled.types.get(i), thread));
            } else {
                args.add(v);
            }
        }
        while (args.size() < PARAM_COUNT) {
            args.add(null);
        }
        try {
            evaluatingThreads.add(thread.uniqueID());
            Value result = host.invokeMethod(thread, m, args, ObjectReference.INVOKE_SINGLE_THREADED);
            return unboxResult(result, thread);
        } catch (InvocationException e) {
            ObjectReference exc = e.exception();
            throw new IllegalStateException("Evaluation threw " + exc.referenceType().name());
        } finally {
            evaluatingThreads.remove(thread.uniqueID());
        }
    }

    /** The eval slots return boxed values (__box); core needs primitives —
     *  a BooleanValue for conditional breakpoints, and primitives format
     *  properly in the REPL. Unbox in the target via the wrapper's xValue(). */
    private Value unboxResult(Value raw, ThreadReference thread) {
        if (!(raw instanceof ObjectReference)) {
            return raw;
        }
        String typeName = ((ObjectReference) raw).referenceType().name();
        Method xvalue;
        if ("java.lang.Integer".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "intValue", "()I");
        } else if ("java.lang.Long".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "longValue", "()J");
        } else if ("java.lang.Double".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "doubleValue", "()D");
        } else if ("java.lang.Float".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "floatValue", "()F");
        } else if ("java.lang.Boolean".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "booleanValue", "()Z");
        } else if ("java.lang.Character".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "charValue", "()C");
        } else if ("java.lang.Byte".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "byteValue", "()B");
        } else if ("java.lang.Short".equals(typeName)) {
            xvalue = firstMethod((ObjectReference) raw, "shortValue", "()S");
        } else {
            return raw;
        }
        if (xvalue == null) {
            return raw;
        }
        try {
            return ((ObjectReference) raw).invokeMethod(thread, xvalue, Collections.emptyList(),
                ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (Exception e) {
            return raw; // honest fallback: hand core the boxed object
        }
    }

    private Method firstMethod(ObjectReference ref, String name, String sig) {
        for (Method m : ref.referenceType().methodsByName(name)) {
            if (sig.equals(m.signature())) {
                return m;
            }
        }
        return null;
    }

    private Value boxInTarget(Value raw, String typeName, ThreadReference thread) throws Exception {
        ClassType wrapper = wrapperClass(typeName);
        if (wrapper == null) {
            return raw;
        }
        Method valueOf = null;
        for (Method m : wrapper.methodsByName("valueOf")) {
            if (m.signature().equals(boxSig(typeName))) {
                valueOf = m;
                break;
            }
        }
        if (valueOf == null) {
            throw new IllegalStateException("No valueOf for " + typeName);
        }
        return wrapper.invokeMethod(thread, valueOf,
            Collections.singletonList(raw), ObjectReference.INVOKE_SINGLE_THREADED);
    }

    private ClassType wrapperClass(String typeName) {
        String boxed;
        switch (typeName) {
            case "int":     boxed = "java.lang.Integer"; break;
            case "byte":    boxed = "java.lang.Byte"; break;
            case "short":   boxed = "java.lang.Short"; break;
            case "long":    boxed = "java.lang.Long"; break;
            case "double":  boxed = "java.lang.Double"; break;
            case "float":   boxed = "java.lang.Float"; break;
            case "boolean": boxed = "java.lang.Boolean"; break;
            case "char":    boxed = "java.lang.Character"; break;
            default:        return null;
        }
        List<ReferenceType> types = vm.classesByName(boxed);
        return types.isEmpty() ? null : (ClassType) types.get(0);
    }

    private static String boxSig(String typeName) {
        switch (typeName) {
            case "int":     return "(I)Ljava/lang/Integer;";
            case "byte":     return "(B)Ljava/lang/Byte;";
            case "short":    return "(S)Ljava/lang/Short;";
            case "long":    return "(J)Ljava/lang/Long;";
            case "double":  return "(D)Ljava/lang/Double;";
            case "float":   return "(F)Ljava/lang/Float;";
            case "boolean": return "(Z)Ljava/lang/Boolean;";
            case "char":    return "(C)Ljava/lang/Character;";
            default:        return "";
        }
    }

    private void ensureHostLoaded(ClassLoaderReference frameLoader) throws Exception {
        if (hostLoaded) {
            return;
        }
        if (!vm.classesByName("JdapEvalHost").isEmpty()) {
            hostLoaded = true;
            return;
        }
        ClassType javaClass = (ClassType) vm.classesByName("java.lang.Class").get(0);
        Method forName = javaClass.methodsByName("forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;").get(0);
        List<Value> args = new ArrayList<>();
        args.add(vm.mirrorOf("JdapEvalHost"));
        args.add(vm.mirrorOf(true));
        args.add(frameLoader);
        try {
            javaClass.invokeMethod(vm.allThreads().isEmpty() ? null : vm.allThreads().get(0),
                forName, args, ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (InvocationException e) {
            throw new IllegalStateException("JdapEvalHost is not on the debuggee classpath. "
                + "Launch the debuggee with jdap-evalhost.jar on the classpath "
                + "(Class.forName reported " + e.exception().referenceType().name() + ").");
        }
        if (vm.classesByName("JdapEvalHost").isEmpty()) {
            throw new IllegalStateException("JdapEvalHost did not load in the target VM.");
        }
        hostLoaded = true;
    }

    private void compileHost(String source, int slot) throws Exception {
        scratch.mkdirs();
        File srcFile = new File(scratch, "JdapEvalHost.java");
        Files.write(srcFile.toPath(), source.getBytes(StandardCharsets.UTF_8));
        String javac = System.getProperty("java.home") + File.separator + "bin" + File.separator + "javac";
        List<String> cmd = new ArrayList<>();
        cmd.add(javac);
        cmd.add("-nowarn");
        cmd.add("-cp");
        cmd.add(targetClasspath() + File.pathSeparator + scratch.getAbsolutePath());
        cmd.add("-d");
        cmd.add(scratch.getAbsolutePath());
        cmd.add(srcFile.getAbsolutePath());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("javac rejected the expression: " + firstLines(out, 6));
        }
        byte[] bytes = Files.readAllBytes(new File(scratch, "JdapEvalHost.class").toPath());
        ReferenceType host = vm.classesByName("JdapEvalHost").get(0);
        vm.redefineClasses(Collections.singletonMap(host, bytes));
    }

    private void restorePristineHost() throws Exception {
        try (InputStream in = EvalEngine.class.getResourceAsStream("/JdapEvalHost.class")) {
            if (in == null) {
                return; // pristine bytes unavailable; keep the working host
            }
            byte[] pristine = in.readAllBytes();
            ReferenceType host = vm.classesByName("JdapEvalHost").get(0);
            if (host != null) {
                vm.redefineClasses(Collections.singletonMap(host, pristine));
            }
        }
        Arrays.fill(slotBusy, false);
        cache.clear();
    }

    private int freeSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!slotBusy[i]) {
                return i;
            }
        }
        return -1;
    }

    private String targetClasspath() throws Exception {
        if (targetClasspath == null) {
            ClassType sys = (ClassType) vm.classesByName("java.lang.System").get(0);
            Method getProp = sys.methodsByName("getProperty", "(Ljava/lang/String;)Ljava/lang/String;").get(0);
            ThreadReference any = vm.allThreads().isEmpty() ? null : vm.allThreads().get(0);
            Value v = sys.invokeMethod(any, getProp,
                Collections.singletonList(vm.mirrorOf("java.class.path")), ObjectReference.INVOKE_SINGLE_THREADED);
            targetClasspath = v instanceof StringReference ? ((StringReference) v).value() : ".";
        }
        return targetClasspath;
    }

    private static String rewriteThis(String expression, boolean hasSelf) {
        if (!hasSelf || expression == null) {
            return expression;
        }
        StringBuilder sb = new StringBuilder();
        boolean inString = false, inChar = false, escaped = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                escaped = inString || inChar;
                continue;
            }
            if (c == '"' && !inChar) {
                inString = !inString;
            } else if (c == '\'' && !inString) {
                inChar = !inChar;
            }
            if (!inString && !inChar
                    && expression.startsWith("this", i)
                    && (i == 0 || !Character.isJavaIdentifierPart(expression.charAt(i - 1)))
                    && (i + 4 >= expression.length() || !Character.isJavaIdentifierPart(expression.charAt(i + 4)))) {
                sb.append("__self");
                i += 3;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static void appendBoxOverloads(StringBuilder sb) {
        String[][] overloads = {
            {"Object", "return v;"}, {"int", "return Integer.valueOf(v);"},
            {"long", "return Long.valueOf(v);"}, {"double", "return Double.valueOf(v);"},
            {"float", "return Float.valueOf(v);"}, {"boolean", "return Boolean.valueOf(v);"},
            {"char", "return Character.valueOf(v);"}, {"byte", "return Byte.valueOf(v);"},
            {"short", "return Short.valueOf(v);"},
        };
        for (String[] o : overloads) {
            sb.append("    public static Object __box(").append(o[0]).append(" v) { ").append(o[1]).append(" }\n");
        }
    }

    private static String localsShape(StackFrame frame) {
        try {
            StringBuilder sb = new StringBuilder();
            for (LocalVariable v : frame.visibleVariables()) {
                sb.append(v.typeName()).append(",");
            }
            return sb.toString();
        } catch (AbsentInformationException e) {
            return "noinfo";
        }
    }

    private static String firstLines(String s, int n) {
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }

    static class CompiledEval {
        final int slot;
        final List<String> names;
        final List<String> types;
        final boolean hasSelf;
        final String declaringType;
        CompiledEval(int slot, List<String> names, List<String> types, boolean hasSelf, String declaringType) {
            this.slot = slot;
            this.names = names;
            this.types = types;
            this.hasSelf = hasSelf;
            this.declaringType = declaringType;
        }
    }
}
