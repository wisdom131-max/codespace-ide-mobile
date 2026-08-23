# VN Code

> A mobile-first VS Code alternative for Android. Code, build, and ship entirely from your phone.

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84)]()
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20Material%203-blue)]()
[![Backend](https://img.shields.io/badge/backend-NestJS%20%7C%20PostgreSQL-red)]()
[![License](https://img.shields.io/badge/license-MIT-green)]()

VN Code is a production-grade IDE that adapts the VS Code experience to
Android phones. It connects to GitHub, GitHub Codespaces, Docker containers, and
remote Linux servers over SSH, ships a real terminal, a multi-tab code editor with
syntax highlighting, full Git workflows, and an AI assistant that supports OpenAI,
Claude, Gemini, DeepSeek, and local Ollama models.

---

## 📦 Repository layout

```
codespace-ide-mobile/
├── README.md                  ← you are here
├── docs/                      ← the 10 deliverables (architecture, schema, API, etc.)
│   ├── 01-architecture.md
│   ├── 02-database-schema.md
│   ├── 03-api-design.md
│   ├── 04-android-frontend.md
│   ├── 05-backend-services.md
│   ├── 06-authentication.md
│   ├── 07-deployment.md
│   ├── 08-apk-build.md
│   ├── 09-error-handling.md
│   └── 10-scalability.md
├── android/                   ← Kotlin + Jetpack Compose app (the APK source)
├── backend/                   ← NestJS + PostgreSQL services
└── .github/workflows/         ← CI that builds & signs the APK
```

## 🚀 Quick start

### Build the APK (three options — pick one)

**Option A — Android Studio (easiest)**
```bash
# 1. Open android/ in Android Studio (Hedgehog or newer)
# 2. Let Gradle sync, then: Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s)
# Output: android/app/build/outputs/apk/release/app-release.apk
```

**Option B — Command line**
```bash
cd android
./gradlew assembleRelease       # unsigned/dev-signed
# or a signed release (see docs/08-apk-build.md for keystore setup):
./gradlew assembleRelease -Pandroid.injected.signing.store.file=...
```

**Option C — CI (no local toolchain needed)**
Push to GitHub. The workflow in `.github/workflows/android-build.yml` builds and
signs the APK in the cloud and uploads it as an artifact / release asset.

> See **`docs/08-apk-build.md`** for the full keystore + signing walkthrough.

### Run the backend
```bash
cd backend
cp .env.example .env            # fill in secrets
docker compose up -d            # Postgres + Redis
npm install
npm run start:dev               # http://localhost:8080
```

## 🧭 Feature matrix

| Area | Capabilities |
|------|--------------|
| **Source control** | Clone, pull, push, commit, branch, merge, PRs, diff viewer |
| **Remote** | GitHub, Codespaces, SSH servers, Docker containers, SFTP/FTP |
| **Editor** | Multi-tab, syntax highlighting (12+ langs), split-screen, search & replace, format/lint |
| **Terminal** | Bash/Node/Python/Git/npm/pnpm/yarn over WebSocket PTY, floating mode |
| **AI** | Explain / generate / refactor / fix / docs / tests / project-context chat; 5 providers |
| **Mobile** | Tuned for 3–8 GB RAM, gestures, hardware keyboard, offline editing |
| **Cloud** | Remote sync, background sync, automatic backups |
| **Tools** | Browser preview, Markdown preview, DB/SQLite/JSON editors, API tester, snippets |
| **Extensibility** | Plugin marketplace + VS Code-style extension system, modular core |

## 🏗️ Architecture at a glance

```
┌────────────────────────────── Android App (Compose) ──────────────────────────────┐
│  UI (Material 3)  ·  Editor engine  ·  Terminal  ·  Git (JGit)  ·  AI client       │
│  Local store (Room/SQLite)  ·  Offline cache  ·  Plugin host                       │
└───────────────┬───────────────────────────────────────────────┬───────────────────┘
                │ HTTPS / WSS                                     │ SSH / SFTP
        ┌───────▼────────┐                              ┌─────────▼─────────┐
        │  Backend (Nest)│                              │  Remote servers    │
        │  Auth · Sync   │  ── OAuth/API ──▶  GitHub /   │  Codespaces /      │
        │  AI proxy · PTY│                   AI vendors  │  Docker hosts      │
        └───────┬────────┘                              └────────────────────┘
        ┌───────▼────────┐
        │ PostgreSQL/Redis│
        └────────────────┘
```

Full details in [`docs/01-architecture.md`](docs/01-architecture.md).

## ⚖️ Honest scope note
An `.apk` is a *compiled artifact*. This repository contains the complete, buildable
source and a one-command path (local or CI) to produce a **signed, production APK**.
No build toolchain is bundled inside this workspace, so the binary is produced on your
machine or in CI — see `docs/08-apk-build.md`.

## 📄 License
MIT — no subscription gates, no artificial project/file-size limits. See feature docs.
