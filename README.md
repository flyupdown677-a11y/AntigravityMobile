# Google Antigravity Mobile for Android (API 29+)

An Android-compatible implementation and client architecture for **Google Antigravity Agent & Interactions API**, featuring Google-managed cloud Linux container sandboxes (`antigravity-preview-09-2026`), background task execution, Android Scoped Storage integration, and multi-account Gemini API key management.

---

## 1. Architecture Overview

Antigravity Mobile allows developers and power users to run Google's autonomous coding agent directly from an Android smartphone:

```
┌────────────────────────────────────────────────────────┐
│               Android Jetpack Compose UI               │
│      ChatScreen • AccountDialog • ModelSelector        │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│                 AntigravityBridge                      │
│        Reactive Flow Dispatcher & Session State        │
└────────────┬─────────────────────────────┬─────────────┘
             │ (Default / Recommended)     │ (Local Mode)
             ▼                             ▼
┌───────────────────────────┐ ┌──────────────────────────┐
│   InteractionsApiService   │ │  Headless PRoot Debian   │
│  Google GenAI REST API    │ │   Local Container Engine │
│  (Cloud Linux Sandbox)    │ │   (ptrace Virtualization)│
└───────────────────────────┘ └──────────────────────────┘
```

### 1. Cloud-Native Managed Agent (Primary Engine)
Powered by the official Google GenAI Interactions API:
- **Agent**: `antigravity-preview-09-2026`
- **Underlying Models**: **Gemini 3.8 Flash** (default fast reasoning) and **Gemini 4 Argon** (frontier reasoning for complex software architecture and debugging).
- **Cloud Linux Container**: Automatically provisions a remote Ubuntu container with pre-installed runtimes (Python 3.12, Node.js 22, git, curl, ripgrep, jq, gcloud CLI).
- **Persistent Sandboxes**: Environments can be preserved across turns via `environment_id`, allowing incremental development, package installations, and builds.
- **Unrestricted Egress**: Outbound network allowlist allows the cloud container to install packages via `pip`/`npm`, clone git repos, and query web APIs.

### 2. Multi-Account Key Management
- Backed by **Android Keystore (AES-256 GCM)** and `EncryptedSharedPreferences`.
- Fast 1-tap switching between accounts and API keys directly from the top app bar.

---

## 2. Interactions API Integration

The app connects directly to the Google Interactions endpoint:

```http
POST https://generativelanguage.googleapis.com/v1beta/interactions
Content-Type: application/json
x-goog-api-key: <YOUR_GEMINI_API_KEY>

{
  "agent": "antigravity-preview-09-2026",
  "input": [{"type": "text", "text": "Build a benchmark script and run it"}],
  "environment": {
    "type": "remote",
    "network": {
      "allowlist": [{"domain": "*"}]
    }
  },
  "background": true,
  "tools": [
    {"type": "code_execution"},
    {"type": "google_search"},
    {"type": "url_context"}
  ],
  "agent_config": {
    "type": "antigravity",
    "model": "gemini-3.8-flash"
  }
}
```

The Android client polls `GET https://generativelanguage.googleapis.com/v1beta/interactions/{id}` reactively, streaming live thoughts, tool executions, and final output into the Compose chat timeline.

---

## 3. Project Structure

```text
/app/
├── app/
│   ├── build.gradle.kts                # Android build config (minSdk 29, targetSdk 35)
│   └── src/main/
│       ├── AndroidManifest.xml         # Foreground service, network, and storage permissions
│       └── java/com/antigravity/mobile/
│           ├── data/
│           │   ├── model/
│           │   │   ├── Account.kt      # Account, Project, and ChatMessage models
│           │   │   └── InteractionModels.kt # Google Interactions API request/response schemas
│           │   ├── remote/
│           │   │   └── InteractionsApiService.kt # HTTP polling & step streaming client
│           │   └── repository/
│           │       ├── AccountRepository.kt # Multi-account encrypted persistence
│           │       └── ProjectRepository.kt # Storage workspace management
│           ├── engine/
│           │   ├── AntigravityBridge.kt# Dual-mode bridge (Cloud API + Local IPC)
│           │   ├── AntigravityService.kt# Android Foreground Service for persistence
│           │   └── ContainerManager.kt # Optional local PRoot Debian manager
│           ├── ui/
│           │   ├── MainActivity.kt     # App entry point & lifecycle handler
│           │   ├── screens/
│           │   │   ├── ChatScreen.kt   # Chat UI, streaming text, reasoning bubbles
│           │   │   └── AccountDialog.kt# Multi-account switch & model selector modal
│           │   └── theme/Theme.kt      # Material 3 theme
```

---

## 4. Building & Running

### Requirements
- Android SDK 29+ (Android 10 to Android 15)
- JDK 17
- Gemini API Key from [Google AI Studio](https://aistudio.google.com/)

### Build Debug APK
```bash
./gradlew assembleDebug
```
Output APK is located at:
`app/build/outputs/apk/debug/app-debug.apk`
