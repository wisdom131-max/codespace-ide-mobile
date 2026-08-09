package com.codespace.ide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub OAuth Device Flow sign-in. No redirect URI and no client secret needed — the user
 * sees a short code, enters it at github.com/login/device on any device/browser, and we poll
 * until they approve. This is what lets Source Control actually authenticate git push/pull.
 *
 * Setup (one-time, 2 minutes): github.com/settings/developers -> "New OAuth App" -> any
 * name/homepage URL -> Authorization callback URL can be anything (e.g. https://github.com,
 * it's never hit by this flow) -> after creating, click "Enable Device Flow" -> copy the
 * Client ID (NOT the client secret — device flow token exchange needs no secret) into
 * CLIENT_ID below.
 */
object GitHubAuth {

    // OAuth App "Visual Node Code" — Device Flow verified working. Client IDs
    // are public identifiers, safe to ship in the app — this is not the client secret.
    const val CLIENT_ID = "Ov23liEA2inOMzi7bYrJ" // "Visual Node Code" OAuth App — verified working 2026-08-09

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Int,
        val pollIntervalSeconds: Int,
    )

    data class RepoInfo(
        val name: String,
        val fullName: String,
        val cloneUrl: String,
        val isPrivate: Boolean,
        val updatedAt: String,
        val description: String?,
        val stars: Int,
    )

    private sealed interface PollResult {
        data class Success(val accessToken: String) : PollResult
        data object Pending : PollResult
        data object SlowDown : PollResult
        data object ExpiredOrDenied : PollResult
    }

    /** Step 1: ask GitHub for a device code + the short user-facing code. */
    suspend fun requestDeviceCode(scope: String = "repo read:user"): DeviceCode = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", scope)
            .build()
        val resp = http.newCall(
            Request.Builder()
                .url("https://github.com/login/device/code")
                .header("Accept", "application/json")
                .post(body)
                .build()
        ).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (!resp.isSuccessful || json.has("error")) {
            throw Exception(
                json.optString("error_description", "Failed to start GitHub sign-in (HTTP ${resp.code}). " +
                    "Make sure GitHubAuth.CLIENT_ID is set to a real OAuth App Client ID with Device Flow enabled.\n\nSetup: github.com/settings/developers -> New OAuth App -> Enable Device Flow -> copy Client ID.")
            )
        }
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresInSeconds = json.getInt("expires_in"),
            pollIntervalSeconds = json.optInt("interval", 5),
        )
    }

    private suspend fun pollOnce(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val resp = http.newCall(
            Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(body)
                .build()
        ).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        when {
            json.has("access_token") -> PollResult.Success(json.getString("access_token"))
            json.optString("error") == "authorization_pending" -> PollResult.Pending
            json.optString("error") == "slow_down" -> PollResult.SlowDown
            else -> PollResult.ExpiredOrDenied
        }
    }

    /**
     * Step 2: poll until the user approves at github.com/login/device, denies, or the code
     * expires. Suspends for the whole wait — call from a coroutine scope tied to a dismissible
     * dialog so the user can cancel.
     */
    suspend fun pollForToken(device: DeviceCode): String {
        var intervalMs = device.pollIntervalSeconds * 1000L
        val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            when (val result = pollOnce(device.deviceCode)) {
                is PollResult.Success -> return result.accessToken
                PollResult.Pending -> { /* keep polling at the same interval */ }
                PollResult.SlowDown -> intervalMs += 5_000L
                PollResult.ExpiredOrDenied -> throw Exception("Sign-in was denied or the code expired. Try again.")
            }
        }
        throw Exception("Code expired before you approved it. Try again.")
    }

    /** Fetches the signed-in user's login name, for display ("Connected as wisdom131-max"). */
    suspend fun fetchUsername(accessToken: String): String = withContext(Dispatchers.IO) {
        val resp = http.newCall(
            Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute()
        if (!resp.isSuccessful) throw Exception("Signed in, but couldn't fetch your GitHub username (HTTP ${resp.code}).")
        JSONObject(resp.body?.string() ?: "{}").optString("login", "GitHub user")
    }

    /**
     * Fetches the signed-in user's repositories, sorted by last updated.
     * Returns up to 100 repos (one page — sufficient for the in-app browser).
     */
    suspend fun listUserRepos(accessToken: String): List<RepoInfo> = withContext(Dispatchers.IO) {
        val resp = http.newCall(
            Request.Builder()
                .url("https://api.github.com/user/repos?sort=updated&per_page=100&type=owner")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute()
        if (!resp.isSuccessful) throw Exception("Failed to fetch repos (HTTP ${resp.code})")
        val body = resp.body?.string() ?: "[]"
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            RepoInfo(
                name = obj.optString("name", "?"),
                fullName = obj.optString("full_name", obj.optString("name", "?")),
                cloneUrl = obj.optString("clone_url", ""),
                isPrivate = obj.optBoolean("private", false),
                updatedAt = obj.optString("updated_at", ""),
                description = obj.optString("description").ifBlank { null },
                stars = obj.optInt("stargazers_count", 0),
            )
        }
    }

    /**
     * Creates a new GitHub repository under the signed-in user's account.
     * Returns the repo's clone URL (https://github.com/<owner>/<name>.git).
     * Called from SourceControlPane's "Publish to GitHub" flow after git init.
     */
    suspend fun createRepo(
        accessToken: String,
        repoName: String,
        description: String = "",
        isPrivate: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject()
            .put("name", repoName)
            .put("description", description)
            .put("private", isPrivate)
            .put("auto_init", false)  // we already have local commits
            .toString()
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val resp = http.newCall(
            Request.Builder()
                .url("https://api.github.com/user/repos")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .post(body)
                .build()
        ).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (!resp.isSuccessful) {
            val msg = json.optString("message", "HTTP ${resp.code}")
            throw Exception("Failed to create repo: $msg")
        }
        json.getString("clone_url")  // e.g. https://github.com/wisdom131-max/my-repo.git
    }
}
