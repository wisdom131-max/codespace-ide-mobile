package com.codespace.ide.ssh

import com.codespace.ide.domain.AppError
import com.codespace.ide.domain.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class SshCredentials(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
)

/**
 * Direct on-device SSH via SSHJ.
 *
 * P3 fixes:
 *  - Resource leak: SSHClient.disconnect() called in finally if auth throws
 *  - PromiscuousVerifier replaced with TOFU (trust-on-first-use) fingerprint store
 *  - openShell(): connect() wrapped in try/catch; error emitted as a line to the UI
 *  - openShell(): buffered byte reads replace readLine() so partial-line prompts arrive
 */
@Singleton
class SshManager @Inject constructor() {

    // TOFU: fingerprints accepted by the user, keyed by "host:port"
    private val knownFingerprints = mutableMapOf<String, String>()

    /**
     * Connect and authenticate. On any failure after the socket is open,
     * [SSHClient.disconnect] is called to avoid a resource leak.
     */
    private fun connect(creds: SshCredentials): SSHClient {
        val ssh = SSHClient()

        // TOFU verifier: accept unknown hosts on first connect and remember fingerprint;
        // reject on subsequent connects if fingerprint changed (basic MITM protection).
        val key = "${creds.host}:${creds.port}"
        ssh.addHostKeyVerifier { hostname, port, key2 ->
            val fp = key2.fingerprint
            val stored = knownFingerprints[key]
            if (stored == null) {
                knownFingerprints[key] = fp   // trust on first use
                true
            } else {
                stored == fp                   // reject if changed
            }
        }

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
            // P3 fix: close socket before re-throwing to prevent leak
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
     * P3 fixes:
     *  - connect() failures caught and emitted as "[SSH Error] ..." lines
     *  - Reader uses a byte buffer instead of readLine() so partial-line prompts arrive
     */
    fun openShell(
        creds: SshCredentials,
        onReady: (OutputStream) -> Unit,
    ): Flow<String> = callbackFlow {
        val ssh: SSHClient
        val session: Session
        val shell: Session.Shell

        try {
            ssh = connect(creds)
            session = ssh.startSession()
            session.allocateDefaultPTY()
            shell = session.startShell()
        } catch (t: Throwable) {
            trySend("[SSH Error] ${t.message ?: "Connection failed"}\r\n")
            close()
            return@callbackFlow
        }

        onReady(shell.outputStream)

        // P3 fix: byte-buffer read so shell prompts (no trailing \n) arrive immediately
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
