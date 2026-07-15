package com.codespace.ide.ssh

import android.content.Context
import android.util.Base64
import com.codespace.ide.domain.AppError
import com.codespace.ide.domain.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

data class SshCredentials(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
)

// ── TOFU fingerprint store ────────────────────────────────────────────────────
// Persists accepted host fingerprints to filesDir/ssh-known-hosts.json.
// Key = "host:port", value = "SHA256:<base64>".
// Thread-safe: all reads/writes happen on Dispatchers.IO via the caller.
object SshFingerprintStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "ssh-known-hosts.json")

    fun load(ctx: Context): MutableMap<String, String> {
        val f = file(ctx)
        if (!f.exists()) return mutableMapOf()
        return try {
            val obj = JSONObject(f.readText())
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.getString(k) }
            map
        } catch (_: Exception) { mutableMapOf() }
    }

    fun save(ctx: Context, map: Map<String, String>) {
        try {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            file(ctx).writeText(obj.toString(2))
        } catch (_: Exception) { /* best-effort */ }
    }

    fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val b64 = Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
        return "SHA256:$b64"
    }
}

// ── SshManager ────────────────────────────────────────────────────────────────
/**
 * Direct on-device SSH via SSHJ.
 *
 * P14-D: TOFU fingerprint pinning replaces PromiscuousVerifier.
 *  - First connect to a host: fingerprint is stored, connection accepted.
 *  - Subsequent connects: fingerprint compared; mismatch = reject + warning emitted.
 *  - Fingerprints stored in filesDir/ssh-known-hosts.json.
 *  - getKnownHosts() / removeFingerprint() exposed for the SSH Manager UI.
 *
 * P3 fixes retained:
 *  - Resource leak: SSHClient.disconnect() called in finally if auth throws.
 *  - openShell(): byte-buffer reads replace readLine() so partial-line prompts arrive.
 *  - openShell(): connect() errors caught and emitted as "[SSH Error] ..." lines.
 */
@Singleton
class SshManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // In-memory fingerprint cache; loaded lazily on first connect.
    // Guarded by synchronized(fingerprintLock) on every access.
    private val fingerprintLock = Any()
    private var fingerprintsLoaded = false
    private val knownFingerprints = mutableMapOf<String, String>()

    private fun ensureLoaded() {
        synchronized(fingerprintLock) {
            if (!fingerprintsLoaded) {
                knownFingerprints.putAll(SshFingerprintStore.load(context))
                fingerprintsLoaded = true
            }
        }
    }

    /** Returns a copy of all stored host:port → SHA256 fingerprint entries. */
    fun getKnownHosts(): Map<String, String> {
        ensureLoaded()
        return synchronized(fingerprintLock) { knownFingerprints.toMap() }
    }

    /** Removes a stored fingerprint so the next connect triggers a fresh TOFU accept. */
    fun removeFingerprint(hostPort: String) {
        ensureLoaded()
        synchronized(fingerprintLock) {
            knownFingerprints.remove(hostPort)
            SshFingerprintStore.save(context, knownFingerprints)
        }
    }

    /**
     * TOFU HostKeyVerifier:
     * - Unknown host → store fingerprint → accept.
     * - Known host, same fingerprint → accept.
     * - Known host, different fingerprint → REJECT (possible MITM).
     * Returns the rejection reason via [onMismatch] so callers can surface it.
     */
    private inner class TofuVerifier(
        private val onMismatch: (String) -> Unit = {},
    ) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            ensureLoaded()
            val hostPort = "$hostname:$port"
            val fp = SshFingerprintStore.fingerprint(key)
            return synchronized(fingerprintLock) {
                val stored = knownFingerprints[hostPort]
                when {
                    stored == null -> {
                        // First time seeing this host — trust and remember.
                        knownFingerprints[hostPort] = fp
                        SshFingerprintStore.save(context, knownFingerprints)
                        true
                    }
                    stored == fp -> true
                    else -> {
                        onMismatch(hostPort)
                        false
                    }
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            emptyList()
    }

    /**
     * Connect and authenticate. On any failure after the socket is open,
     * [SSHClient.disconnect] is called to avoid a resource leak.
     *
     * @param onMismatch called if the remote host key fingerprint changed since
     *   the last accepted connection (possible MITM). Connection is rejected.
     */
    private fun connect(
        creds: SshCredentials,
        onMismatch: (String) -> Unit = {},
    ): SSHClient {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(TofuVerifier(onMismatch))
        ssh.connect(creds.host, creds.port)
        try {
            when {
                creds.privateKeyPem != null ->
                    ssh.authPublickey(creds.username, ssh.loadKeys(creds.privateKeyPem, null, null))
                creds.password != null ->
                    ssh.authPassword(creds.username, creds.password)
                else -> error("No authentication method provided")
            }
        } catch (t: Throwable) {
            runCatching { ssh.disconnect() }
            throw t
        }
        return ssh
    }

    suspend fun testConnection(creds: SshCredentials): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                connect(creds).use { /* connected and authenticated */ }
                AppResult.Success(Unit)
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Ssh(t.message ?: "SSH connection failed"))
            }
        }

    suspend fun runCommand(creds: SshCredentials, command: String): AppResult<String> =
        withContext(Dispatchers.IO) {
            try {
                connect(creds).use { ssh ->
                    ssh.startSession().use { session ->
                        val cmd = session.exec(command)
                        val output = cmd.inputStream.bufferedReader().readText()
                        cmd.join()
                        AppResult.Success(output)
                    }
                }
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Ssh(t.message ?: "Command failed"))
            }
        }

    /**
     * Opens an interactive PTY shell. Emits output bytes as UTF-8 strings.
     * Write to [onReady]'s OutputStream to send stdin.
     *
     * P14-D: emits a prominent warning line if the host fingerprint changed
     *   (connection is rejected by the TOFU verifier before it gets this far,
     *   but the warning line is added via onMismatch callback before close()).
     *
     * P3: byte-buffer read so shell prompts (no trailing \n) arrive immediately.
     */
    fun openShell(
        creds: SshCredentials,
        onReady: (OutputStream) -> Unit,
    ): Flow<String> = callbackFlow {
        val ssh: SSHClient
        val session: Session
        val shell: Session.Shell

        var mismatchHost: String? = null
        try {
            ssh = connect(creds, onMismatch = { hostPort -> mismatchHost = hostPort })
            session = ssh.startSession()
            session.allocateDefaultPTY()
            shell = session.startShell()
        } catch (t: Throwable) {
            val msg = if (mismatchHost != null)
                "[SSH WARNING] Host key changed for $mismatchHost — connection rejected.\r\n" +
                "[SSH WARNING] If this is intentional, go to Settings → SSH Manager and remove the stored fingerprint for $mismatchHost.\r\n"
            else
                "[SSH Error] ${t.message ?: "Connection failed"}\r\n"
            trySend(msg)
            close()
            return@callbackFlow
        }

        onReady(shell.outputStream)

        // P3: byte-buffer read so shell prompts (no trailing \n) arrive immediately.
        val readerThread = Thread {
            val buf = ByteArray(4096)
            try {
                val stream = shell.inputStream
                var n = stream.read(buf)
                while (n > 0) {
                    trySend(String(buf, 0, n, Charsets.UTF_8))
                    n = stream.read(buf)
                }
            } catch (_: Throwable) { /* closed */ }
        }.apply { isDaemon = true; start() }

        awaitClose {
            runCatching { shell.close() }
            runCatching { session.close() }
            runCatching { ssh.disconnect() }
            readerThread.interrupt()
        }
    }.flowOn(Dispatchers.IO)
}
