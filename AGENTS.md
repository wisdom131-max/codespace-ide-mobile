# CodeSpace IDE Mobile — AI Agent Instructions

## Purpose
Help AI coding agents understand this repository quickly and make productive changes to the Android app, backend service, and deployment docs.

## Repository structure
- `android/` — native Android app source. Kotlin + Jetpack Compose + Material 3, Hilt DI, Room, Coroutines/Flow, JGit, terminal, AI client, plugin host.
- `backend/` — NestJS API service. TypeScript + TypeORM, PostgreSQL, Redis, JWT auth, GitHub integration, AI proxy, WebSocket PTY, sync service.
- `docs/` — architecture, API, backend services, deployment, APK build, error handling, scalability.
- `README.md` — quick start, high-level architecture, features, build overview.

## Primary tasks
- Fix or extend Android UI/features in `android/app/src/main/java/com/codespace/ide/` and Gradle build logic in `android/app/build.gradle.kts`.
- Fix or extend backend API, auth, AI, terminal, or sync logic in `backend/src/`.
- Update docs in `docs/` to match implementation or clarify architecture.
- Avoid editing generated binaries or signing artifacts.

## Build and test commands
- Android local build: `cd android && ./gradlew assembleRelease`
- Android Play bundle: `cd android && ./gradlew bundleRelease`
- Backend local dev: `cd backend && npm install && npm run start:dev`
- Backend lint: `cd backend && npm run lint`
- Backend tests: `cd backend && npm test`
- Backend database migrations:
  - `cd backend && npm run migration:run`
  - `cd backend && npm run migration:generate`
- Backend local dependencies: `cd backend && docker compose up -d` (Postgres + Redis)

## Important documents
- `docs/01-architecture.md`
- `docs/04-android-frontend.md`
- `docs/05-backend-services.md`
- `docs/07-deployment.md`
- `docs/08-apk-build.md`

## Key conventions
- Android is written in Kotlin with modern Compose and follows MVVM + unidirectional state flow.
- Backend is a NestJS monolith using modules, DTO validation, global exception filters, and pino logging.
- AI support is provider-agnostic: OpenAI, Claude, Gemini, DeepSeek, Ollama.
- Do not commit secrets. Backend config uses `.env` and the Android build reads `keystore.properties`.
- The repository does not bundle the Android SDK/toolchain; local APK builds require JDK 17 and Android SDK 34.

## Where to look first
- `android/app/src/main/java/com/codespace/ide/` for Compose screens, editor, terminal, AI UI, and feature wiring.
- `backend/src/` for NestJS controllers, services, auth, AI, terminal gateway, and sync logic.
- `android/app/build.gradle.kts` for app flavors, packaging, and signing config.
- `backend/package.json` for backend scripts and dependency commands.

## Notes for agents
- Prefer linking to existing docs instead of copying their content.
- Keep changes minimal and aligned with mobile-first, offline-capable behavior.
- When editing features, preserve the separation between Android frontend and backend APIs.
