import * as AuthSession from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';
import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';
import { githubService } from './github';

WebBrowser.maybeCompleteAuthSession();

// GitHub OAuth App settings
// You MUST create an OAuth App at: https://github.com/settings/developers
// Set callback URL to: codespaceside://auth
const GITHUB_CLIENT_ID = 'Ov23liD32rC2d0v897L2'; // <-- replace this

const discovery = {
  authorizationEndpoint: 'https://github.com/login/oauth/authorize',
  tokenEndpoint: 'https://github.com/login/oauth/access_token',
  revocationEndpoint: 'https://github.com/settings/connections/applications',
};

export class AuthService {
  static async signInWithGitHub() {
    const redirectUri = AuthSession.makeRedirectUri({
      scheme: 'codespaceside',
      path: 'auth',
    });

    const state = await Crypto.digestStringAsync(
      Crypto.CryptoDigestAlgorithm.SHA256,
      Math.random().toString()
    );

    const authUrl =
      `https://github.com/login/oauth/authorize` +
      `?client_id=${GITHUB_CLIENT_ID}` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&scope=repo,codespace,user,gist,read:org,workflow` +
      `&state=${state}`;

    const result = await WebBrowser.openAuthSessionAsync(authUrl, redirectUri);

    if (result.type === 'success') {
      const url = result.url;
      const params = new URLSearchParams(url.split('?')[1]);
      const code = params.get('code');

      if (code) {
        // Exchange code for token via your backend proxy
        // (GitHub requires client_secret which can't be in mobile apps)
        // You need a simple proxy server or use GitHub Device Flow instead
        return { code, needsExchange: true };
      }
    }

    return null;
  }

  // GitHub Device Flow - works without a backend proxy!
  static async signInWithDeviceFlow() {
    try {
      // Step 1: Request device code
      const deviceRes = await fetch('https://github.com/login/device/code', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: JSON.stringify({
          client_id: GITHUB_CLIENT_ID,
          scope: 'repo codespace user gist read:org workflow',
        }),
      });

      const deviceData = await deviceRes.json();
      return deviceData; // Returns { device_code, user_code, verification_uri, interval, expires_in }
    } catch (err) {
      throw new Error('Failed to start device flow: ' + err.message);
    }
  }

  static async pollForToken(deviceCode, interval = 5) {
    try {
      const tokenRes = await fetch('https://github.com/login/oauth/access_token', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: JSON.stringify({
          client_id: GITHUB_CLIENT_ID,
          device_code: deviceCode,
          grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
        }),
      });

      const data = await tokenRes.json();

      if (data.access_token) {
        await githubService.setToken(data.access_token);
        return { success: true, token: data.access_token };
      }

      if (data.error === 'authorization_pending') {
        return { success: false, pending: true };
      }

      if (data.error === 'slow_down') {
        return { success: false, pending: true, slowDown: true };
      }

      return { success: false, error: data.error };
    } catch (err) {
      return { success: false, error: err.message };
    }
  }

  // Sign in with Personal Access Token (simplest approach)
  static async signInWithPAT(token) {
    try {
      await githubService.setToken(token);
      const user = await githubService.getUser();
      await SecureStore.setItemAsync('github_user', JSON.stringify(user));
      return { success: true, user };
    } catch (err) {
      await githubService.clearToken();
      throw new Error('Invalid token or network error');
    }
  }

  static async signOut() {
    await githubService.clearToken();
    await SecureStore.deleteItemAsync('github_user');
  }

  static async getSavedUser() {
    try {
      const raw = await SecureStore.getItemAsync('github_user');
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }
}

export default AuthService;
