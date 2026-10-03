/**
 * F6-b: the pristine eval host. This class must be on the DEBUGGEE's classpath
 * (shipped as /opt/jdap/jdap-evalhost.jar). The driver force-loads it in the
 * target VM, then redefines a FIXED eval slot's method BODY per compiled
 * expression. JVMTI redefineClasses on this target cannot add or delete
 * methods (canAddMethod=false), so the host ships with six pre-declared slots
 * (eval0..eval5), each with an immutable signature — only bodies change.
 *
 * Slot contract:
 *  a0      = frame's `this` (null for static frames)
 *  a1..a7 = frame locals in declaration order; primitives arrive boxed
 *  the redefined body unboxes them and returns __box(expression)
 */
public class JdapEvalHost {
    public static void ping() { /* load marker for selftest */ }

    public static Object eval0(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) throws Throwable {
        throw new UnsupportedOperationException("jdap eval slot 0 is empty");
    }

    public static Object eval1(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) throws Throwable {
        throw new UnsupportedOperationException("jdap eval slot 1 is empty");
    }

    public static Object eval2(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) throws Throwable {
        throw new UnsupportedOperationException("jdap eval slot 2 is empty");
    }

    public static Object eval3(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) throws Throwable {
        throw new UnsupportedOperationException("jdap eval slot 3 is empty");
    }

    public static Object eval4(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) throws Throwable {
        throw new UnsupportedOperationException("jdap eval slot 4 is empty");
    }

    public static Object eval5(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) throws Throwable {
        throw new UnsupportedOperationException("jdap eval slot 5 is empty");
    }

    public static Object __box(Object v) { return v; }
    public static Object __box(int v) { return Integer.valueOf(v); }
    public static Object __box(long v) { return Long.valueOf(v); }
    public static Object __box(double v) { return Double.valueOf(v); }
    public static Object __box(float v) { return Float.valueOf(v); }
    public static Object __box(boolean v) { return Boolean.valueOf(v); }
    public static Object __box(char v) { return Character.valueOf(v); }
    public static Object __box(byte v) { return Byte.valueOf(v); }
    public static Object __box(short v) { return Short.valueOf(v); }
}
