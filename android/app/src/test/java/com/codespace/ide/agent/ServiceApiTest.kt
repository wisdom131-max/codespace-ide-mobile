package com.codespace.ide.agent

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P3 / Item 32: the external-service layer, tested without a single live credential and without a
 * real service.
 *
 *  - **Planning** is pure, so every security rule (https, host binding, placeholder validation, the
 *    read/write split, body bounds, a missing credential) is asserted directly rather than inferred.
 *  - **Execution** runs against a loopback stub HTTP server. `requireHttps = false` is the documented
 *    test seam; production passes nothing and gets the https rule.
 *
 * `com.sun.net.httpserver` is not on the Android unit-test compile classpath, and a new dependency is
 * not worth it for this, so [LocalHttpStub] speaks just enough HTTP/1.1 for one request at a time.
 */
class ServiceApiTest {

    private lateinit var stub: LocalHttpStub

    private val token = "ghp_test_token_do_not_leak_1234567890"

    @Before fun setUp() {
        ServiceApi.resetRateLimits()
        stub = LocalHttpStub()
    }

    @After fun tearDown() {
        stub.close()
    }

    private fun serve(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        delayMs: Long = 0,
    ): String {
        stub.status = status
        stub.body = body
        stub.headers = headers
        stub.delayMs = delayMs
        return stub.base
    }

    private fun secrets(vararg pairs: Pair<String, String>): (String) -> String? = { name ->
        pairs.toMap()[name]
    }

    private fun planOk(
        service: String,
        operation: String,
        vars: Map<String, String> = emptyMap(),
        body: String? = null,
        write: Boolean = false,
        secrets: (String) -> String? = secrets(
            "github_token" to token, "gitlab_token" to token, "api_token" to token,
            "bitbucket_token" to token, "gitea_token" to token,
        ),
        plain: Boolean = false,
    ): ServiceApi.Plan = ServiceApi.plan(service, operation, vars, body, secrets, write, requireHttps = !plain)

    // ── planning ────────────────────────────────────────────────────────────────────────────────

    @Test fun anUnknownServiceIsRefusedWithTheListOfKnownOnes() {
        val plan = planOk("sourcehut", "repo")
        assertNotNull(plan.refusal)
        assertTrue(plan.refusal!!.contains("github"))
        assertTrue(plan.refusal!!.contains("gitlab"))
    }

    @Test fun anUnknownOperationIsRefusedWithTheServicesOwnList() {
        val plan = planOk("github", "delete_everything")
        assertNotNull(plan.refusal)
        assertTrue(plan.refusal!!.contains("issue_create"))
    }

    @Test fun aWriteThroughTheReadToolIsRefusedAndViceVersa() {
        val writeViaRead = planOk("github", "issue_create", mapOf("owner" to "o", "repo" to "r"), "{\"title\":\"x\"}")
        assertNotNull(writeViaRead.refusal)
        assertTrue(writeViaRead.refusal!!.contains("service_write"))

        val readViaWrite = planOk("github", "repo", mapOf("owner" to "o", "repo" to "r"), write = true)
        assertNotNull(readViaWrite.refusal)
        assertTrue(readViaWrite.refusal!!.contains("service_read"))
    }

    @Test fun aReadPlansTheExpectedUrlAndMethod() {
        val plan = planOk("github", "repo", mapOf("owner" to "octocat", "repo" to "hello"))
        assertNull(plan.refusal)
        assertEquals("https://api.github.com/repos/octocat/hello", plan.url)
        assertEquals("GET", plan.method)
        assertEquals(ServiceApi.Effect.READ, plan.effect)
    }

    @Test fun queryValuesAreEncodedRatherThanInjected() {
        val plan = planOk("github", "search_code", mapOf("q" to "crash on launch"))
        assertNull(plan.refusal)
        assertTrue(plan.url.endsWith("?q=crash%20on%20launch"))

        val injected = planOk("github", "search_code", mapOf("q" to "a&admin=true"))
        assertNotNull(injected.refusal)
    }

    @Test fun pathValuesCannotEscapeTheOperation() {
        val traversal = planOk("github", "contents", mapOf("owner" to "o", "repo" to "r", "path" to "../../etc/passwd"))
        assertNotNull(traversal.refusal)

        val absolute = planOk("github", "contents", mapOf("owner" to "o", "repo" to "r", "path" to "https://evil.tld/x"))
        assertNotNull(absolute.refusal)

        val smuggled = planOk("github", "contents", mapOf("owner" to "o", "repo" to "r", "path" to "x?token=1"))
        assertNotNull(smuggled.refusal)
    }

    @Test fun aMissingCredentialIsNamedSoTheUserKnowsWhatToSet() {
        val plan = planOk("github", "repo", mapOf("owner" to "o", "repo" to "r"), secrets = secrets())
        assertNotNull(plan.refusal)
        assertTrue(plan.refusal!!.contains("github_token"))
        assertFalse("a token must never appear in a refusal", plan.refusal!!.contains(token))
    }

    @Test fun gitlabsCredentialIsCalledOutByItsOwnName() {
        val plan = planOk("gitlab", "project", mapOf("id" to "42"), secrets = secrets("github_token" to token))
        assertNotNull(plan.refusal)
        assertTrue(plan.refusal!!.contains("gitlab_token"))
    }

    @Test fun aCustomServiceNeedsAnHttpsBaseAndNeverCallsAPlainOne() {
        val noBase = planOk("custom", "get", mapOf("path" to "health"), secrets = secrets("api_token" to token))
        assertNotNull(noBase.refusal)
        assertTrue(noBase.refusal!!.contains("api_base_url"))

        val plain = planOk(
            "custom", "get", mapOf("path" to "health"),
            secrets = secrets("api_token" to token, "api_base_url" to "http://internal.example"),
        )
        assertNotNull(plain.refusal)
        assertTrue(plain.refusal!!.contains("https"))

        val good = planOk(
            "custom", "get", mapOf("path" to "health"),
            secrets = secrets("api_token" to token, "api_base_url" to "https://internal.example/api"),
        )
        assertNull(good.refusal)
        assertEquals("https://internal.example/api/health", good.url)
    }

    @Test fun aBodyThatIsNotJsonIsRefusedAndOversizeIsRefused() {
        val notJson = planOk("github", "issue_create", mapOf("owner" to "o", "repo" to "r"), "please open an issue", write = true)
        assertNotNull(notJson.refusal)

        val huge = "{\"title\":\"" + "x".repeat(ServiceApi.MAX_BODY_BYTES) + "\"}"
        val oversized = planOk("github", "issue_create", mapOf("owner" to "o", "repo" to "r"), huge, write = true)
        assertNotNull(oversized.refusal)
    }

    @Test fun aWriteWithoutABodyIsRefusedButDeleteIsAllowed() {
        val noBody = planOk("github", "issue_create", mapOf("owner" to "o", "repo" to "r"), null, write = true)
        assertNotNull(noBody.refusal)

        val delete = planOk(
            "custom", "delete", mapOf("path" to "items/1"),
            secrets = secrets("api_token" to token, "api_base_url" to "https://api.example"),
            write = true,
        )
        assertNull(delete.refusal)
    }

    @Test fun aPlanNeverPrintsItsCredential() {
        val plan = planOk("github", "repo", mapOf("owner" to "o", "repo" to "r"))
        assertNull(plan.refusal)
        assertFalse("toString must not leak the token", plan.toString().contains(token))
        assertEquals("***", plan.token.toString())
    }

    // ── rate limiting ───────────────────────────────────────────────────────────────────────────

    @Test fun aSecondCallInTheSameInstantIsRefused() {
        serve(200, "{\"ok\":true}")
        val plan = planOk(
            "custom", "get", mapOf("path" to "health"),
            secrets = secrets("api_token" to token, "api_base_url" to stub.base),
            plain = true,
        )
        assertNull(plan.refusal)
        val first = ServiceApi.execute(plan, requireHttps = false)
        assertTrue(first.ok)

        val again = ServiceApi.execute(plan, requireHttps = false)
        assertFalse(again.ok)
        assertTrue(again.text.contains("too fast"))
    }

    @Test fun thePerMinuteCeilingIsRecordedOnlyForCallsThatRun() {
        // A refused plan must not consume rate budget: otherwise a model looping on a bad call would
        // lock itself out of the good one.
        ServiceApi.resetRateLimits()
        val refused = ServiceApi.plan("github", "repo", mapOf("owner" to "o", "repo" to "r"), null, { null }, false)
        assertNotNull(refused.refusal)
        for (i in 0 until ServiceApi.MAX_CALLS_PER_MINUTE + 5) ServiceApi.execute(refused)
        assertNull(ServiceApi.rateRefusal("github", System.currentTimeMillis()))
    }

    // ── execution ───────────────────────────────────────────────────────────────────────────────

    private fun runAgainst(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        delayMs: Long = 0,
        readTimeoutMs: Int = ServiceApi.READ_TIMEOUT_MS,
    ): ServiceApi.Outcome {
        val base = serve(status, body, headers, delayMs)
        val plan = planOk(
            "custom", "get", mapOf("path" to "health"),
            secrets = secrets("api_token" to token, "api_base_url" to base),
            plain = true,
        )
        assertNull(plan.refusal)
        return ServiceApi.execute(plan, requireHttps = false, readTimeoutMs = readTimeoutMs)
    }

    @Test fun aSuccessfulReadIsLabelledAsUntrusted() {
        val out = runAgainst(200, "{\"status\":\"ok\"}")
        assertTrue(out.ok)
        assertEquals(200, out.status)
        assertTrue("the model must be told this is data", out.text.contains("untrusted"))
        assertTrue(out.text.contains("\"status\":\"ok\""))
        assertEquals("Bearer " + token, stub.lastAuthorization)
        assertEquals("GET", stub.lastMethod)
    }

    @Test fun aServiceThatEchoesTheTokenDoesNotGetItIntoTheTranscript() {
        val out = runAgainst(200, "debug: your header was " + token)
        assertFalse("an echoed credential must be scrubbed", out.text.contains(token))
        assertTrue(out.text.contains("***"))
    }

    @Test fun aRedirectIsReportedAndNotFollowed() {
        val out = runAgainst(302, "", mapOf("Location" to "https://evil.example/steal"))
        assertFalse(out.ok)
        assertTrue(out.text.contains("redirect"))
        assertTrue(out.text.contains("evil.example"))
        assertEquals("the credential is not re-sent to the redirect target", 1, stub.requestCount)
    }

    @Test fun aRejectedCredentialIsExplainedNotDumped() {
        val out = runAgainst(401, "{\"message\":\"Bad credentials\"}")
        assertFalse(out.ok)
        assertEquals(401, out.status)
        assertTrue(out.text.contains("401"))
        assertTrue(out.text.contains("Bad credentials"))
        assertFalse(out.text.contains(token))
    }

    @Test fun rateLimitingFromTheServiceCarriesItsRetryHint() {
        val out = runAgainst(429, "slow down", mapOf("Retry-After" to "30"))
        assertFalse(out.ok)
        assertTrue(out.text.contains("429"))
        assertTrue(out.text.contains("30"))
    }

    @Test fun anOversizedResponseIsTruncatedWithANotice() {
        val big = "{\"data\":\"" + "x".repeat(ServiceApi.MAX_RESPONSE_BYTES + 5000) + "\"}"
        val out = runAgainst(200, big)
        assertTrue(out.ok)
        assertTrue("the model must know it is partial", out.text.contains("truncated"))
        assertTrue(out.text.length < ServiceApi.MAX_RESPONSE_BYTES + 2000)
    }

    @Test fun aSlowServiceTimesOutWithoutBeingRetried() {
        val out = runAgainst(200, "late", delayMs = 1500, readTimeoutMs = 400)
        assertFalse(out.ok)
        assertTrue(out.text.contains("Timed out"))
        assertFalse("nothing is retried automatically", out.text.contains("retrying"))
    }

    @Test fun nonJsonFromTheServiceIsPassedThroughAsData() {
        val out = runAgainst(200, "<html><body>not json at all</body></html>")
        assertTrue(out.ok)
        assertTrue(out.text.contains("untrusted"))
        assertTrue(out.text.contains("not json at all"))
    }

    @Test fun aWriteSendsItsBodyAndMethodThroughTheSameGuard() {
        val base = serve(201, "{\"number\":7}")
        val plan = planOk(
            "custom", "post", mapOf("path" to "issues"),
            body = "{\"title\":\"hello\"}",
            write = true,
            secrets = secrets("api_token" to token, "api_base_url" to base),
            plain = true,
        )
        assertNull(plan.refusal)
        val out = ServiceApi.execute(plan, requireHttps = false)
        assertTrue(out.ok)
        assertEquals("POST", stub.lastMethod)
        assertEquals("{\"title\":\"hello\"}", stub.lastBody)
    }

    @Test fun aRefusedPlanNeverOpensASocket() {
        val plan = planOk("github", "repo", mapOf("owner" to "o", "repo" to "r"), secrets = secrets())
        assertNotNull(plan.refusal)
        val out = ServiceApi.execute(plan)
        assertFalse(out.ok)
        assertTrue(out.text.contains("github_token"))
        assertEquals("no request reached the network", 0, stub.requestCount)
    }


    // ── registry invariants (Item 31: the checks that catch a careless addition) ─────────────────

    @Test fun everyOperationIsWellFormed() {
        val methods = setOf("GET", "POST", "PUT", "DELETE")
        assertTrue("at least the three original services must exist", ServiceApi.SERVICES.size >= 3)
        for (service in ServiceApi.SERVICES) {
            assertTrue("a service needs an id", service.id.isNotBlank())
            assertTrue("a service needs a credential name", service.secretName.isNotBlank())
            assertTrue("a service needs at least one operation", service.operations.isNotEmpty())
            val ids = service.operations.map { it.id }
            assertEquals(service.id + " has a duplicate operation id", ids.size, ids.toSet().size)
            for (op in service.operations) {
                assertTrue(service.id + "/" + op.id + " has a method outside the allowlist: " + op.method, op.method in methods)
                assertTrue(service.id + "/" + op.id + " must use a relative path", op.path.startsWith("/") && !op.path.contains("://"))
                assertTrue(service.id + "/" + op.id + " needs a summary", op.summary.isNotBlank())
                // A static base must already be https - a definition cannot introduce a plain host.
                if (service.baseUrl.isNotBlank()) assertTrue(service.baseUrl, service.baseUrl.startsWith("https://"))
            }
            // A service with a configurable base must name the secret that holds it.
            if (service.baseUrl.isBlank()) assertNotNull(service.id + " has a blank base but no baseSecret", service.baseSecret)
        }
    }

    @Test fun everyPlaceholderInATemplateIsAValidName() {
        for (service in ServiceApi.SERVICES) {
            for (op in service.operations) {
                for (template in listOf(op.path, op.query)) {
                    Regex("\\{([^}]*)\\}").findAll(template).forEach { m ->
                        val name = m.groupValues[1]
                        assertTrue(service.id + "/" + op.id + " has a malformed placeholder {" + name + "}",
                            Regex("^[a-zA-Z][a-zA-Z0-9_]*$").matches(name))
                    }
                }
            }
        }
    }


    // ── Item 33: the added services, and the auth style each one builds ─────────────────────────

    @Test fun eachServiceBuildsItsOwnAuthHeader() {
        val gh = planOk("github", "repo", mapOf("owner" to "o", "repo" to "r"))
        assertEquals("Authorization", gh.authHeaderName)
        assertEquals("Bearer ", gh.authPrefix)
        assertEquals(token, gh.token!!.value)

        val gl = planOk("gitlab", "project", mapOf("id" to "42"))
        assertEquals("PRIVATE-TOKEN", gl.authHeaderName)
        assertEquals("", gl.authPrefix)

        val gitea = planOk(
            "gitea", "repo", mapOf("owner" to "o", "repo" to "r"),
            secrets = secrets("gitea_token" to token, "gitea_base_url" to "https://git.example.com/api/v1"),
        )
        assertNull(gitea.refusal)
        assertEquals("Authorization", gitea.authHeaderName)
        assertEquals("token ", gitea.authPrefix)

        val bitbucket = planOk("bitbucket", "repo", mapOf("workspace" to "w", "slug" to "s"))
        assertNull(bitbucket.refusal)
        assertEquals("Bearer ", bitbucket.authPrefix)
        assertEquals("https://api.bitbucket.org/2.0/repositories/w/s", bitbucket.url)
    }

    @Test fun jiraUsesBasicAuthAndNeedsBothSecrets() {
        val base = "https://site.atlassian.net"
        val noEmail = planOk(
            "jira", "issue", mapOf("key" to "VN-1"),
            secrets = secrets("jira_token" to token, "jira_base_url" to base),
        )
        assertNotNull(noEmail.refusal)
        assertTrue(noEmail.refusal!!.contains("jira_email"))

        val plan = planOk(
            "jira", "issue", mapOf("key" to "VN-1"),
            secrets = secrets("jira_token" to token, "jira_email" to "me@example.com", "jira_base_url" to base),
        )
        assertNull(plan.refusal)
        assertEquals("Basic ", plan.authPrefix)
        assertEquals("https://site.atlassian.net/rest/api/3/issue/VN-1", plan.url)
        // Basic carries base64(email:token), never the token alone, and the plan still redacts it.
        assertTrue(plan.token!!.value.startsWith("bWVAZXhhbXBsZS5jb206"))
        assertFalse(plan.toString().contains(plan.token!!.value))
        assertEquals(token, plan.scrub!!.value)
    }

    @Test fun aSelfHostedServiceWithoutBaseIsRefusedByName() {
        val gitea = planOk("gitea", "repo", mapOf("owner" to "o", "repo" to "r"), secrets = secrets("gitea_token" to token))
        assertNotNull(gitea.refusal)
        assertTrue(gitea.refusal!!.contains("gitea_base_url"))

        val plain = planOk(
            "gitea", "repo", mapOf("owner" to "o", "repo" to "r"),
            secrets = secrets("gitea_token" to token, "gitea_base_url" to "http://git.example.com/api/v1"),
        )
        assertNotNull(plain.refusal)
        assertTrue(plain.refusal!!.contains("https"))
    }

    @Test fun jqlIsEncodedSoItCannotAddAParameter() {
        val plan = planOk(
            "jira", "search", mapOf("jql" to "project = VN AND status = Open"),
            secrets = secrets("jira_token" to token, "jira_email" to "me@example.com", "jira_base_url" to "https://s.atlassian.net"),
        )
        assertNull(plan.refusal)
        assertTrue(plan.url.contains("jql=project%20%3D%20VN%20AND%20status%20%3D%20Open"))
        assertFalse(plan.url.substringAfter("?").contains("&"))
    }

    @Test fun theCiOperationsAreClassifiedAndBodylessOnesPlan() {
        // A cancel is a write even though it carries no body, and it may not go through the read tool.
        val viaRead = planOk("github", "run_cancel", mapOf("owner" to "o", "repo" to "r", "run_id" to "9"))
        assertNotNull(viaRead.refusal)
        assertTrue(viaRead.refusal!!.contains("service_write"))

        val cancel = planOk("github", "run_cancel", mapOf("owner" to "o", "repo" to "r", "run_id" to "9"), write = true)
        assertNull(cancel.refusal)
        assertEquals("POST", cancel.method)
        assertEquals("https://api.github.com/repos/o/r/actions/runs/9/cancel", cancel.url)
        assertNull(cancel.body)

        val retry = planOk("gitlab", "pipeline_retry", mapOf("id" to "1", "pipeline_id" to "2"), write = true)
        assertNull(retry.refusal)

        val jobs = planOk("github", "run_jobs", mapOf("owner" to "o", "repo" to "r", "run_id" to "9"))
        assertNull(jobs.refusal)
        assertEquals(ServiceApi.Effect.READ, jobs.effect)

        val logs = planOk("gitlab", "job_logs", mapOf("id" to "1", "job_id" to "2"))
        assertNull(logs.refusal)
        assertEquals("https://gitlab.com/api/v4/projects/1/jobs/2/trace", logs.url)
    }


    // ── L20: one credential-free hop for a signed URL ────────────────────────────────────────────

    @Test fun aSameHostRedirectIsFollowedWithoutTheCredential() {
        val stub = LocalHttpStub()
        stub.sequence = listOf(302, 200)
        stub.headers = mapOf("Location" to (stub.base + "/signed/logs"))
        stub.body = "JOB LOG BODY"
        val plan = planOk(
            "custom", "get", mapOf("path" to "health"),
            secrets = secrets("api_base_url" to stub.base, "api_token" to token),
            plain = true,
        )
        assertNull(plan.refusal)
        val outcome = ServiceApi.execute(plan, requireHttps = false)
        assertTrue(outcome.ok)
        assertTrue(outcome.text.contains("JOB LOG BODY"))
        assertEquals(2, stub.requestCount)
        // The second request carried no Authorization header: that is the whole point of the hop.
        assertNull(stub.lastAuthorization)
        stub.close()
    }

    @Test fun aRedirectToAnotherHostIsStillRefused() {
        val stub = LocalHttpStub()
        stub.status = 302
        stub.headers = mapOf("Location" to "https://objects.githubusercontent.com/signed?sig=1")
        val plan = planOk(
            "custom", "get", mapOf("path" to "health"),
            secrets = secrets("api_base_url" to stub.base, "api_token" to token),
            plain = true,
        )
        val outcome = ServiceApi.execute(plan, requireHttps = false)
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("objects.githubusercontent.com"))
        assertTrue(outcome.text.contains("not custom's own host"))
        assertEquals(1, stub.requestCount)
        stub.close()
    }

    @Test fun aRedirectOnAWriteIsNeverReSend() {
        val stub = LocalHttpStub()
        stub.sequence = listOf(302, 200)
        stub.headers = mapOf("Location" to (stub.base + "/signed"))
        val plan = planOk(
            "custom", "post", mapOf("path" to "items"), body = "{\"a\":1}", write = true,
            secrets = secrets("api_base_url" to stub.base, "api_token" to token),
            plain = true,
        )
        val outcome = ServiceApi.execute(plan, requireHttps = false)
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("Only a GET is followed"))
        assertEquals(1, stub.requestCount)
        stub.close()
    }

    @Test fun productionNeverFollowsAPlainHttpRedirectBecauseItNeverSendsAPlainRequest() {
        // The https rule is one gate covering the first request AND the hop. In production
        // (requireHttps) a plain-http base is refused before any socket is opened, so a plain-http
        // redirect can never be reached.
        val stub = LocalHttpStub()
        stub.status = 302
        stub.headers = mapOf("Location" to "http://127.0.0.1:1/x")
        val plan = planOk(
            "custom", "get", mapOf("path" to "health"),
            secrets = secrets("api_base_url" to stub.base, "api_token" to token),
            plain = true,
        )
        val outcome = ServiceApi.execute(plan)  // production default: requireHttps = true
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("only https is allowed"))
        assertEquals(0, stub.requestCount)
        stub.close()
    }

    @Test fun githubNowOffersRunLogs() {
        val plan = planOk("github", "run_logs", mapOf("owner" to "o", "repo" to "r", "run_id" to "9"))
        assertNull(plan.refusal)
        assertEquals("https://api.github.com/repos/o/r/actions/runs/9/logs", plan.url)
    }

    // ── the stub ────────────────────────────────────────────────────────────────────────────────

    /** One-request-at-a-time HTTP/1.1 stub. Enough for a client that does not reuse connections. */
    internal class LocalHttpStub : Closeable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        @Volatile var status: Int = 200

        /** Status for request N (1-based); falls back to [status] when the list is shorter. */
        @Volatile var sequence: List<Int> = emptyList()
        private val served = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var body: String = ""
        @Volatile var headers: Map<String, String> = emptyMap()
        @Volatile var delayMs: Long = 0
        @Volatile var lastMethod: String? = null
        @Volatile var lastPath: String? = null
        @Volatile var lastAuthorization: String? = null
        @Volatile var lastBody: String? = null
        @Volatile var requestCount: Int = 0

        val base: String get() = "http://127.0.0.1:" + socket.localPort

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { handle(client) }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun handle(client: Socket) {
            client.use { c ->
                requestCount++
                val nth = served.incrementAndGet()
                val thisStatus = sequence.getOrElse(nth - 1) { status }
                val input = BufferedInputStream(c.getInputStream())
                val requestLine = readLine(input) ?: return
                lastMethod = requestLine.substringBefore(' ')
                lastPath = requestLine.split(' ').getOrNull(1)
                var contentLength = 0
                val seen = HashMap<String, String>()
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    val name = header.substringBefore(':').trim()
                    val value = header.substringAfter(':').trim()
                    seen[name.lowercase()] = value
                    if (name.equals("Content-Length", ignoreCase = true)) contentLength = value.toIntOrNull() ?: 0
                }
                lastAuthorization = seen["authorization"]
                lastBody = if (contentLength > 0) String(readN(input, contentLength), Charsets.UTF_8) else ""
                if (delayMs > 0) Thread.sleep(delayMs)
                val bytes = body.toByteArray()
                val head = StringBuilder("HTTP/1.1 " + thisStatus + " Status\r\n")
                head.append("Content-Length: ").append(bytes.size).append("\r\n")
                head.append("Connection: close\r\n")
                headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                head.append("\r\n")
                val out: OutputStream = c.getOutputStream()
                out.write(head.toString().toByteArray())
                out.write(bytes)
                out.flush()
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.isEmpty()) null else line.toString()
                if (b == '\n'.code) return line.toString().trimEnd('\r')
                line.append(b.toChar())
            }
        }

        private fun readN(input: BufferedInputStream, n: Int): ByteArray {
            val buffer = ByteArray(n)
            var read = 0
            while (read < n) {
                val got = input.read(buffer, read, n - read)
                if (got < 0) break
                read += got
            }
            return buffer.copyOf(read)
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
