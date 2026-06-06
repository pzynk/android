# Sync Android App

A modern, material-designed Android client for the **Android ↔ Desktop Sync** ecosystem. This application is built with **Kotlin**, **Android SDK**, **Material You (Dynamic Colors)**, and **Coroutines**, enabling seamless synchronization and integration with the Sync Desktop companion application over the local network.

---

## Key Features

- **Local Network P2P Discovery**: Automatically discovers and securely pairs with desktop clients on the local network via UDP broadcasting (port `8200`) and TCP sockets (port `8080`).
- **Universal Clipboard Sync**: Dynamically synchronizes clipboard content between the desktop and Android device in the background.
- **File & Media Sharing**: Send files, media, and images directly to and from your desktop. Uses Android's `FileProvider` to intercept native Android share intents (`SEND` / `SEND_MULTIPLE`).
- **Remote Desktop Media Control**: Monitor desktop playback and control volume levels from the palm of your hand.
- **Terminal Execution & AI Agent**: Run secure shell commands on your desktop, capture screenshots, and query an integrated Gemini-powered AI agent (`AiAgent.kt`) to translate natural language queries into native desktop commands.
- **Quick Settings Tile**: Adds a system quick settings tile to trigger immediate clipboard synchronization.

---

## Project Structure

The codebase is structured under the `sols.sync` package namespace:

```text
app/
├── app/src/main/
│   ├── java/sols/sync/
│   │   ├── network/          # TCP/UDP connection and pairing protocols
│   │   │   ├── model/        # Broadcast models and protocol envelopes
│   │   │   ├── protocol/     # ServerMessage schemas and state handling
│   │   │   └── TcpClient.kt  # Low-level TCP connections and event loop
│   │   ├── system/           # OS-level integration and background services
│   │   │   ├── AiAgent.kt    # Gemini-powered agent using Google AI Edge SDK
│   │   │   ├── SyncService.kt# Foreground service keeping the P2P connection alive
│   │   │   └── ClipboardTileService.kt # Quick settings tile controller
│   │   ├── ui/               # Activities, view adapters, and UI layers
│   │   │   ├── MainActivity.kt        # Main device discovery list dashboard
│   │   │   ├── DeviceDetailActivity.kt # Device status and feature portal
│   │   │   ├── TerminalActivity.kt    # Secure shell interface
│   │   │   └── AgentActivity.kt       # Conversation interface with the AI Agent
│   │   └── SyncApp.kt        # Application subclass holding the global device/state cache
│   └── res/                  # Layout layouts, strings, menus, and XML definitions
├── gradle/                   # Dependency Catalog (libs.versions.toml)
├── build.gradle.kts          # Root gradle config
└── settings.gradle.kts       # Subproject and repository config
```

---

## Firebase Configuration & Integration

This project integrates **Firebase AI** (Google AI Edge SDK) for running the conversational developer agent locally, and **Firebase Analytics** for usage insights.

### Setting Up Firebase for the App

To build and run the app with Firebase capabilities enabled:

1. **Create a Firebase Project**:
   - Go to the [Firebase Console](https://console.firebase.google.com/).
   - Click **Add Project** and follow the steps to create a new project.

2. **Register the Android App**:
   - In your Firebase Project overview, click the Android icon to add an app.
   - Enter `sols.sync` as the **Android package name**. (Must match the `applicationId` in `app/build.gradle.kts`).
   - Click **Register App**.

3. **Download and Add `google-services.json`**:
   - Download the generated `google-services.json` file.
   - Place this file inside the `app/app/` subdirectory (i.e. `/home/adhil/projects/sync/app/app/google-services.json`).
   - Note: The `com.google.gms.google-services` gradle plugin is already applied to build and compile the application.

4. **Gemini / Google AI API Configuration**:
   - The AI Agent (`AiAgent.kt`) instantiates the Google AI backend:
     ```kotlin
     Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
         modelName = "gemini-3.1-flash-lite",
         ...
     )
     ```
   - Make sure your Google AI API key is configured or set up in your development environment/device variables as per Google/Firebase documentation to enable full functionality of the Gemini-powered agent.

---

## Build & Development

### Prerequisites

- **JDK 11** or newer
- **Android SDK** (Target API Level 36, Minimum API Level 24)
- **Android Studio (Ladybug or newer recommended)**

### Build from Command Line

To assemble the debug application package (APK):

```bash
cd app
./gradlew assembleDebug
```

The compiled APK will be located under `app/app/build/outputs/apk/debug/app-debug.apk`.
