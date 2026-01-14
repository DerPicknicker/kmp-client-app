# iOS Audio Pipeline & MPV Integration Implementation Status

## Overview
This document outlines the architecture and current implementation status of the High-Performance Audio Pipeline for iOS, utilizing `Libmpv` for raw PCM playback via the Sendspin protocol.

## Architecture

The pipeline follows a **Protocol-Delegate pattern** bridging Kotlin Multiplatform (Common/iOS) and Native Swift.

### Data Flow
1.  **Server**: Sends raw PCM chunks over WebSocket.
2.  **`AudioStreamManager` (Kotlin Common)**:
    *   Receives chunks.
    *   Buffers and reorders them (`TimestampOrderedBuffer`, `AdaptiveBufferManager`).
    *   Decodes if necessary (Opus/FLAC) or passes raw PCM.
    *   Calls `MediaPlayerController.writeRawPcm(data)`.
3.  **`MediaPlayerController` (Kotlin Common/iOS)**:
    *   Acts as a facade.
    *   On iOS, delegates to `PlatformPlayerProvider.player` (Global Singleton).
4.  **`PlatformPlayerProvider` (Kotlin iOS)**:
    *   Holds a reference to the native implementation (`PlatformAudioPlayer` interface).
5.  **`MPVController` (Swift)**:
    *   Implements `PlatformAudioPlayer` protocol.
    *   Initializes `Libmpv` (via `MPVKit`).
    *   Manages a **Ring Buffer** (`RingBuffer.swift`) to bridge the push-based Kotlin stream to the pull-based MPV callback.
6.  **`Libmpv` (C-API)**:
    *   Reads from the Ring Buffer via a custom `stream_cb` (stream read callback).
    *   Handles hardware audio output and timing.

## Implementation Details

### Kotlin (Common & iOS)
-   **Refactoring**: Removed Java-specific dependencies (`System.nanoTime`, `PriorityQueue`) from `commonMain` to support iOS compilation.
-   **Synchronization**: Used `kotlinx.coroutines.sync.Mutex` and `TimeSource.Monotonic` for thread safety and timing.
-   **Discovery**: Added `MdnsAdvertiser` expect class for iOS.
-   **Interface**: Defined `PlatformAudioPlayer` interface for Swift to implement.

### Swift (`iosApp`)
-   **`MPVController.swift`**:
    *   Sets up `mpv_handle`.
    *   Configures options: `audio-client-name=sendspin`, `cache=no`, `audio-buffer=0.2`.
    *   Defines `open_stream` to hook into MPV's stream layer using `memory://` or custom protocol logic (currently streaming via direct buffer feed conceptually).
-   **`RingBuffer.swift`**:
    *   Thread-safe circular buffer using `UnsafeMutableRawPointer`.
    *   Handles `write` (from Kotlin) and `read` (from MPV C-callback).
-   **`iOSApp.swift`**:
    *   Instantiates `MPVController`.
    *   Registers it: `PlatformPlayerProvider.shared.player = player`.

## Current Status (2026-01-14)

### ✅ Completed
1.  **Kotlin Compilation**: Fixed all KMP issues preventing iOS framework build.
    *   Gradle task `compileKotlinIosSimulatorArm64` passes.
2.  **Swift Implementation**:
    *   `MPVController` and `RingBuffer` created.
    *   `Libmpv` integration logic in place.
3.  **Dependency Injection**: Wiring between Kotlin and Swift is implemented in `iOSApp.swift`.

### 🚧 Pending / To Verify
1.  **Xcode Build**: User needs to build the iOS app in Xcode to link `ComposeApp.framework` and `Libmpv`.
2.  **Runtime Verification**:
    *   Launch app on Simulator/Device.
    *   Start stream.
    *   Verify audio playback (hear sound).
    *   Monitor logs for buffer underruns/overruns.

## Next Steps
1.  Run `pod install` (if using CocoaPods) or resolve Swift Package Manager dependencies in Xcode.
2.  Build & Run in Xcode.
3.  Test with a live Sendspin stream.
