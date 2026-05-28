# CodeSpace IDE

React Native / Expo app. VS Code-like IDE for Android connected to GitHub Codespaces.

## Stack
- React Native 0.74 + Expo 51
- Zustand (state management)
- React Navigation v6 (stack + drawer)
- Expo SecureStore (token storage)
- Expo AuthSession + WebBrowser (OAuth)
- Axios (GitHub API)

## Key files
- `src/services/github.js` — all GitHub API calls
- `src/services/auth.js`   — GitHub Device Flow auth
- `src/services/codespace.js` — Codespace management
- `src/hooks/useStore.js`  — Zustand global state
- `src/theme/colors.js`    — VS Code Dark+ palette
- `src/screens/EditorScreen.js` — main editor
- `src/components/panels/` — sidebar panels

## Build
`eas build --platform android --profile preview`
