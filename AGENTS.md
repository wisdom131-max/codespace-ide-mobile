# CodeSpace IDE — Build Instructions

## What is this?
A full Android IDE app (React Native / Expo) that works like VS Code on your phone, connected to GitHub Codespaces.

## How to build the APK

### Option A — Expo EAS Build (Recommended, no PC needed)
1. Install EAS CLI: `npm install -g eas-cli`
2. Log in to Expo: `eas login`
3. Configure project: `eas build:configure`
4. Build APK: `eas build --platform android --profile preview`
5. Download APK from the Expo dashboard link provided

### Option B — Local build (requires Android Studio)
1. `npm install`
2. `npx expo prebuild --platform android`
3. `cd android && ./gradlew assembleRelease`
4. APK is at: `android/app/build/outputs/apk/release/app-release.apk`

## Setup steps before building

1. **Create a GitHub OAuth App** at https://github.com/settings/developers
   - Application name: CodeSpace IDE
   - Homepage URL: https://github.com
   - Callback URL: `codespaceside://auth`
   - Copy the Client ID

2. **Update `src/services/auth.js`** — replace `YOUR_GITHUB_CLIENT_ID` with your OAuth App Client ID

3. **Configure EAS project**:
   - Sign up at https://expo.dev
   - Run `eas build:configure` to link your project

## Features
- VS Code Dark+ theme — pixel-perfect
- GitHub OAuth (Device Flow — no server needed)
- Repository browser with full file tree
- Syntax highlighted code editor (JS, TS, Python, Go, Rust, Java, etc.)
- Edit, save & commit files directly to GitHub
- GitHub Codespaces manager (start/stop/connect)
- Integrated terminal (full interactive via connected Codespace)
- Git panel: branches, commits, pull requests
- Extensions panel (install to Codespace)
- Search across repo files
- Profile screen with repo list
- Settings: font size, theme, word wrap, auto-save
- Status bar (branch, language, line/col, dirty indicator)
- Multi-file tabs with dirty indicators
