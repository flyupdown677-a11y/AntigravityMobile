# Google Antigravity Mobile for Android (API 29+)

An Android-compatible implementation and client architecture for **Google Antigravity CLI / SDK**, featuring a transparent user-space Debian Linux virtualization layer (powered by PRoot), Android Scoped Storage integration, and multi-account Gemini API management.

---

## 1. Executive Summary & Analysis of Antigravity CLI

### What is Antigravity CLI?
Google Antigravity CLI (invoked via `agy` or driven programmatically via the `google-antigravity` SDK) is an agentic AI developer tool powered by Gemini models. It provides:
- Multi-step reasoning and automated plan execution.
- File manipulation tools (`create_file`, `edit_file`, `view_file`, `list_dir`, `grep_search`).
- Shell command execution (`run_command`).
- The **`localharness` binary**: A 134 MB compiled Go executable that acts as the core orchestration runtime, exchanging Protobuf messages over standard I/O and hosting a WebSocket IPC server.

### Why Antigravity Does Not Run Natively on Android
1. **Bionic libc vs. GNU glibc**: Android uses Bionic libc, which lacks glibc symbol versioning and standard GNU dynamic linkers (`ld-linux-aarch64.so.1`). Attempting to execute `agy` or `localharness` directly results in `linker: cannot locate symbol` or `exec format error`.
2. **Missing Terminal / User Experience**: Modern smartphone users do not use raw bash terminal sessions. Running a raw CLI requires an interactive terminal emulator, which is cumbersome on touchscreens.
3. **Android 10+ Security Restrictions (W^X & Executable Flags)**:
   - Android 10 (API 29) and later disallow executing binaries from writable storage (`/sdcard` or app data folders not marked as native libraries).
4. **Scoped Storage**:
   - Modern Android isolates files per application. To let users edit project files in external editors (Acode, Spck) or inspect them in Files by Google, the app must manage permissions and bind-mount public shared storage into the container.

---

## 2. Architecture & Solution Overview

### Transparent Virtual Debian Container (Headless PRoot)
Instead of forcing users to navigate a Linux terminal or running a heavyweight VM (KVM/QEMU), the app embeds a lightweight Debian rootfs and user-space **PRoot** virtualization engine:
- **No Root or Kernel VM Required**: PRoot uses `ptrace` system call interception entirely in user space.
- **Syscall & glibc Translation**: PRoot redirects `/`, `/bin`, `/lib`, and `/usr` lookups to the app's private Debian sysroot, allowing standard glibc binaries (`localharness` and Python 3) to execute natively on ARM64 or x86_64 devices.
- **Completely Invisible to User**: No shell prompt or terminal UI is shown. The container runs headlessly as a background service (`AntigravityService`).

### Storage Architecture & Bind-Mounting
- Android public storage: `/storage/emulated/0/AntigravityProjects/`
- Container bind mount: `-b /storage/emulated/0/AntigravityProjects:/workspace`
- When Antigravity modifies or creates code, the changes are written directly to public storage. The user can open their favorite Android file manager or IDE and work with the project files immediately.

### Secure Multi-Account & API Key System
- Backed by **Android Keystore (AES-256 GCM)** and `EncryptedSharedPreferences`.
- Supports multiple distinct accounts/profiles (e.g., Personal, Work, Vertex AI).
- Fast 1-tap switching via the app bar chip. Switching automatically reinitializes the daemon session with the new credentials.

---

## 3. Project Structure

```text
/app/
├── app/
│   ├── build.gradle.kts                # Android build config (minSdk 29, targetSdk 35)
│   └── src/main/
│       ├── AndroidManifest.xml         # Storage, foreground service, internet permissions
│       ├── assets/
│       │   └── antigravity_daemon.py  # Python IPC bridge running inside Debian container
│       └── java/com/antigravity/mobile/
│           ├── data/
│           │   ├── model/Account.kt    # Account, Project, and ChatMessage models
│           │   └── repository/
│           │       ├── AccountRepository.kt # Multi-account encrypted persistence
│           │       └── ProjectRepository.kt # Storage workspace management
│           ├── engine/
│           │   ├── ContainerManager.kt # PRoot & Debian lifecycle controller
│           │   ├── AntigravityBridge.kt# WebSocket IPC client for tokens & thinking
│           │   └── AntigravityService.kt# Android Foreground Service
│           ├── ui/
│           │   ├── MainActivity.kt     # App entry point & permission handler
│           │   ├── screens/
│           │   │   ├── ChatScreen.kt   # Chat UI, streaming text, reasoning bubbles
│           │   │   └── AccountDialog.kt# Multi-account switch & API key modal
│           │   └── theme/Theme.kt      # Material 3 theme
└── setup_debian_rootfs.sh              # Debian rootfs builder script
```

---

## 4. Permission Model (Android 10 to Android 15)

| Android Version | API Level | Mechanism Used | Access Level |
|---|---|---|---|
| Android 10 | API 29 | `android:requestLegacyExternalStorage="true"` + `READ/WRITE_EXTERNAL_STORAGE` | Full read/write to `/sdcard/AntigravityProjects` |
| Android 11+ | API 30 - 35 | `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (`MANAGE_EXTERNAL_STORAGE`) | Full direct filesystem access for user-visible project folders |

---

## 5. Verification & Testing

The Python daemon IPC bridge was verified against `google-antigravity`:
- Binary discovery and execution verified for `localharness`.
- Protobuf schema analysis completed for `HarnessConfig`, `InputConfig`, `OutputConfig`, and `StepUpdate`.
- WebSocket communication protocols implemented to support real-time token streaming and reasoning introspection.
