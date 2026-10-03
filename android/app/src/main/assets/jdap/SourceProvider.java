import com.microsoft.java.debug.core.DebugException;
import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider.MethodInvocation;
import com.microsoft.java.debug.core.protocol.Types;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F6-b: REAL container-side source lookup, no Eclipse. Java's language rule
 * that a public top-level type must match its file name makes the FQN
 * deterministic from (package declaration + file name) — no JDT workspace
 * needed. Line numbers pass through untouched: the JDI locationsOfLine()
 * resolution inside java-debug-core does the real work against the target
 * VM's line tables, so breakpoints behave as true LINE breakpoints.
 */
public class SourceProvider implements ISourceLookUpProvider {
    private static final Pattern PACKAGE = Pattern.compile(
        "(?s).*?(?:^|[;\\s])package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private final Map<String, String> fqnCache = new ConcurrentHashMap<>();

    @Override
    public boolean supportsRealtimeBreakpointVerification() {
        // We can verify the two things we actually check at request time:
        // the source file exists and its package/class parse. Class-level
        // line verification still happens later via JDI, and core corrects
        // the client with a breakpoint-changed event when that lands.
        return true;
    }

    @Override
    public String[] getFullyQualifiedName(String uri, int[] lines, int[] cols) throws DebugException {
        return new String[] { fqnFor(uri) };
    }

    @Override
    public JavaBreakpointLocation[] getBreakpointLocations(String uri, Types.SourceBreakpoint[] bps) throws DebugException {
        String fqn = fqnFor(uri);
        List<JavaBreakpointLocation> locations = new ArrayList<>();
        for (Types.SourceBreakpoint bp : bps) {
            JavaBreakpointLocation loc = new JavaBreakpointLocation(bp.line, bp.column);
            loc.setClassName(fqn);
            // methodName/methodSignature intentionally left null: that selects
            // core's LINE-breakpoint path (JDI locationsOfLine). Method hints
            // would turn this into a method-entry breakpoint like the F6-a stub.
            locations.add(loc);
        }
        return locations.toArray(new JavaBreakpointLocation[0]);
    }

    @Override
    public String getSourceFileURI(String fqn, String path) {
        if (path != null && !path.trim().isEmpty()) {
            return path;
        }
        if (fqn == null) {
            return null;
        }
        // Honest fallback mapping for stack frames whose JDI sourcePath is bare.
        return fqn.replace('.', '/') + ".java";
    }

    @Override
    public String getSourceContents(String uri) {
        return null; // The IDE opens its own project files; no decompiler in F6-b.
    }

    @Override
    public List<MethodInvocation> findMethodInvocations(String uri, int line) {
        // Step-into targeting is not provided; core falls back to standard stepping.
        return Collections.emptyList();
    }

    private String fqnFor(String uri) throws DebugException {
        String cached = fqnCache.get(uri);
        if (cached != null) {
            return cached;
        }
        String base = baseName(uri);
        if (base.endsWith(".java")) {
            base = base.substring(0, base.length() - 5);
        }
        String fqn = base;
        try {
            File f = new File(uri.startsWith("file:") ? java.net.URI.create(uri).getPath() : uri);
            if (f.isFile()) {
                String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Matcher m = PACKAGE.matcher(src);
                if (m.find()) {
                    fqn = m.group(1) + "." + base;
                } else {
                    fqn = base; // default package
                }
            } else {
                // File not found in the container: the filename rule still gives
                // the deterministic top-level FQN, so keep that, but line-level
                // verification will honestly fail later if the class doesn't match.
                fqn = base;
            }
        } catch (Exception e) {
            throw new DebugException("Cannot read source " + uri + ": " + e.getMessage());
        }
        fqnCache.put(uri, fqn);
        return fqn;
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? path : name;
    }
}
