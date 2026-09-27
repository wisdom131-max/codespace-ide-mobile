package com.codespace.ide.data

import kotlinx.serialization.Serializable

// IG09 (2026-09-27): the Retrofit ApiService (login/refresh/listRepos/createPr) and
// its DTOs were DELETED — they had ZERO callers. Auth is Firebase + GitHub device
// flow; every live backend call goes through ConnectorsApiClient. This file keeps
// ONLY AuthResponse, the live kotlinx-serialization shape of the auth-refresh
// response parsed by the OkHttp token-refresh interceptor (di/AppModule.kt).
@Serializable data class AuthResponse(val accessToken: String, val refreshToken: String, val accessTokenExpiresIn: Int)
