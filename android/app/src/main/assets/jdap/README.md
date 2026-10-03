# jdap — CodeSpace JVM DAP driver (proot container side)

F6-a (2026-10-02): DAP driver provisioned into the rootfs. `java-debug-core`
0.53.1 (headless, JDI-backed) + small deps, launcher `/usr/local/bin/jdap`
(Serial GC, 256m cap), `ensureJdap()` idempotent provisioning for existing
rootfs installs. Stub providers validated against the spike driver with an
identical DAP exchange (initialize → attach → breakpoints → stopped →
stackTrace → scopes → variables → continue → terminated).

F6-b (2026-10-02): REAL providers, still no Eclipse, no third JVM:

- **Source lookup (`SourceProvider.java`)**: parses the package declaration +
  file name into the FQN (a Java language rule — deterministic, no JDT
  workspace). Line breakpoints use core's JDI `locationsOfLine` resolution
  (no fake method hints — F6-a's stub turned every bp into a method-entry bp).
  `supportsRealtimeBreakpointVerification = true` (file exists + package/class
  parse); class-level line verification still happens via JDI and core
  corrects the client with a breakpoint-changed event when that lands.
- **Evaluation (`EvalEngine.java`)**: javac-based, zero Eclipse.
  - Fast JDI path for the common conditional shape `local <op> intLiteral`
    (both operand orders) — no compile, milliseconds.
  - Everything else: the expression is woven into the BODY of a pre-declared
    eval slot of `JdapEvalHost` (redefined in the target VM; this target's
    JVMTI cannot add/delete methods, only change bodies), then invoked on the
    paused thread with the frame's `this` + locals (primitives boxed in the
    target, results unboxed so core sees real primitives — conditions need a
    real `BooleanValue`). Frame mirrors are refetched at each use with a
    stale-frame retry (JDI bumps the frame generation on every invoke).
  - Condition eval failure fails OPEN (core stops and surfaces the error to
    the user) — never silently resumes.
  - Host is loaded in the target via `Class.forName`; the debuggee must run
    with `/opt/jdap/jdap-evalhost.jar` on its classpath (honest error
    otherwise). Launch sessions append it (F6-c wires this).
- **HCR + completions: honest stubs** — unsupported in F6-b, documented, not
  hidden behind silent no-ops.

## Honest limitations (F6-b scope)

- Debuggee must be compiled with `-g` (local-variable table) — gradle/maven
  debug builds do this by default; plain `javac` does NOT.
- Unqualified static members resolve via `import static` only when the frame
  class is public AND in a named package (javac cannot import from the
  unnamed package). Otherwise qualify (`Hello.compute(5)`).
- `this.x` is rewritten to `__self.x`; frames with >7 visible locals or
  array locals are not supported (clear error, not silent wrongness).
- First evaluation of an expression+frame-shape compiles with javac (~1-3s);
  repeats reuse the slot. Conditional storms on tight loops pay a JDI
  round-trip per hit (fast path) — F6-d measures real numbers on device.
- Six eval slots per session; exhaustion restores the pristine host.

## Layer 1 (future, owner-gated): JDT-LS route

HCR (hot code replace), debug-console completions, and `this.`-qualified
member completion all need a real Java compiler + index. The route when the
owner approves: provision `org.eclipse.jdt.ls` (headless, needs the same jars
plus JDT core) into the container, drive it headless from the driver the way
VS Code does, and back the three providers with it. Not started — same
go-decision protocol as F6-c/F6-d.

## On-device test (F6-b gate)

Inside the proot container (needs `openjdk-21-jdk-headless` + `python3`):

    jdap-eval-test

Runs 7 checks: line bp stops at the requested line, conditional bp stops only
when true on BOTH engine paths (javac `n % 7 == 0 && n > 8000`, fast `n >
8500`), a negative condition never stops, and paused-session evaluation
(`n`, `n * 2 + 1`, `Hello.compute(5)`) returns correct values. Sandbox result
before packaging: 7/7.
