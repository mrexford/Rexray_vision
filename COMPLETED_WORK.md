# Rexray Vision - Completed Work

This document archives all completed tasks for the project.

- [x] **Task 1: Ensure Consistent Settings Synchronization**
    - [x] **Unconditional Server Broadcasts:**
        - [x] In `CaptureActivity.kt`, modify the `captureRateSeekBar` listener to broadcast settings changes unconditionally.
        - [x] Add a broadcast message to the `analysisImageReader` listener to propagate auto-ISO changes immediately.
    - [x] **Comprehensive Client UI Updates:**
        - [x] In `CaptureActivity.kt`, expand the `updateUIFromNetwork()` function to update all four settings controls (ISO, Shutter, FPS, and Limit), including their corresponding `SeekBar` progress indicators.

- [x] **Task 1: Add Live Settings Display**
    - [x] **Add TextView:** In `activity_capture.xml`, add a new `TextView` (`@+id/settingsDisplayTextView`) constrained above the `switchModeButton`.
    - [x] **Implement Updater Function:** In `CaptureActivity.kt`, create an `updateSettingsDisplay()` function to format and display the current camera settings.
    - [x] **Integrate Updater:** Call the `updateSettingsDisplay()` function whenever settings are loaded, changed by the user, or received over the network.

- [x] **Task 1: Fix Server Crash on Client Connect**
    - [x] **Simplify Layout:** In `activity_capture.xml`, remove the redundant nested `ConstraintLayout` to create a flatter and more efficient view hierarchy, reducing the likelihood of UI race conditions.

- [x] **Task 1: Fix Client Disconnection Loop**
    - [x] **Implement Robust Navigation:** In `CaptureActivity.kt`, modify the `observeNetworkState` function's collector for `isConnectedToPrimary`.
    - [x] **Replace `finish()` Call:** Instead of calling `finish()` directly, use an `Intent` to navigate back to `SetupActivity` with flags to ensure a clean lifecycle transition, then call `finish()`.

- [x] **Task 1: Fix Premature Client Disconnection**
    - [x] **Isolate Faulty Logic:** In `CaptureActivity.kt`, inspect the `onCreate` and `setupUIForRole` methods to identify the logic that incorrectly triggers `disconnectFromPrimary()`.
    - [x] **Correct State Handling:** Ensure the `isConnectedToPrimary` state collector in `onCreate` only triggers a disconnect when the state is `false`.
    - [x] **Ensure UI Stability:** Verify the `switchModeButton`'s `onClickListener` is not being invoked programmatically and is only triggered by user interaction.

- [x] **Task 1: Diagnose Server-Side Connection Drop**
    - [x] **Add Detailed Logging:** Re-instrument the `handleClient` function in `NetworkService.kt` with comprehensive logging to trace the execution flow and identify the point of failure.

- [x] **Task 1: Relocate `switchModeButton`**
    - [x] **Reposition Button:** In `activity_capture.xml`, modify the constraints of the `switchModeButton` to place it on the left side of the screen and center it vertically.
    - [x] **Add Margin:** Ensure the button has a start margin to prevent it from touching the screen edge.

- [x] **Task 1: Fix `switchModeButton` Layout**
    - [x] **Reposition Button:** In `activity_capture.xml`, move the `switchModeButton` to the top-right corner of the screen.
    - [x] **Add Margin:** Add a margin to the `switchModeButton` to ensure it does not touch the edges of the screen.

- [x] **Task 1: Fix Client-Side Connection Logic**
    - [x] **Create Dedicated Client Listener:** In `NetworkService.kt`, create a new function `listenForServerMessages` that reads incoming messages from the server without automatically closing the connection.
    - [x] **Update Connection Logic:** In `NetworkService.kt`, modify the `connectToPrimary` function to call the new `listenForServerMessages` function instead of the server-side `handleClient` function.

- [x] **Task 1: Debug and Fix Server-Side Connection Drop**
    - [x] **Add Detailed Logging:** Instrument the client and server connection logic in `NetworkService.kt` with comprehensive logging to trace the entire negotiation process.
    - [x] **Analyze Server-Side `handleClient`:** Investigate why the connection is terminated immediately after being accepted. The primary suspect is the logic within the `handleClient` function on the server device.
    - [x] **Ensure State Synchronization:** Verify that after a client connects, the server correctly sends its state and the client receives it.

- [x] **Task 2: Improve Application Robustness and UX**
    - [x] **Fix `SetupActivity` Lifecycle:** In `SetupActivity.kt`, remove the `finish()` call from the `observeConnectionState` collector to ensure the activity remains on the back stack.
    - [x] **Add Connection Timeouts:** In `NetworkService.kt`, add a connection timeout to the `connectToPrimary` method to prevent the application from hanging.
- [x] **Feature: Persistent and User-Editable Camera Names**
    - [x] Implemented camera naming logic using a random selection from the `animals` array.
    - [x] Persisted the camera name using `SharedPreferences`.
    - [x] Displayed the camera name in the UI.
    - [x] Allowed the user to regenerate the camera name.
    - [x] Synchronized camera name changes across client and server.
- [x] **UI and UX Enhancements**
    - [x] Rearranged UI elements to prevent overlapping and improve readability.
    - [x] Added a confirmation dialog for switching between Primary and Client modes.
    - [x] Implemented persistence for camera settings (ISO, shutter speed, etc.) across mode switches.
- [x] **Bugfix: Address Camera Re-initialization and Network Observation Issues**
    - [x] **`IllegalStateException` on Mode Switch**: Modified `CaptureActivity` to call `recreate()` when switching from primary to client mode to ensure proper resource release and prevent race conditions.
    - [x] **`NullPointerException` on Capture**: Move the `observeNetworkState()` call in `CaptureActivity` to the `onOpened` callback of the `CameraDevice.StateCallback` to ensure the camera is fully initialized before processing network commands.

- [x] **Bugfix: Resolve Client-Side Command Handling**
    - [x] **Client Not Responding**: Ensure `observeNetworkState()` in `CaptureActivity` is correctly placed to allow clients to act on server commands (`ArmCapture`, `DisarmCapture`, `StartCapture`).
- [x] **Bugfix: Connection and State-Management Issues**
    - [x] **Premature Socket Closure:** Removed the `recreate()` call from the `showSwitchModeDialog` in `CaptureActivity.kt` to prevent the `NetworkService` from being unnecessarily restarted.
    - [x] **Lingering Service Discovery:** Added `networkService?.stopDiscovery()` to the `onStop()` method of `SetupActivity.kt` to ensure discovery is properly terminated.
    - [x] **Inconsistent Project Name:** Modified `CaptureActivity.kt` to persist and restore the `currentProjectName` using `SharedPreferences`, ensuring the name is stable across mode switches.
    - [x] **Redundant Discovery Calls:** Refined the `discoverButton`'s `onClickListener` in `SetupActivity.kt` to prevent multiple, simultaneous discovery operations.
- [x] **Task 1: Implement Robust Client Workflow & State Management**
    - [x] **Implement Connection State Tracking:** In `NetworkService.kt`, introduced a `StateFlow` named `isConnectedToPrimary` to track the client's connection status to a primary server.
    - [x] **Automate Client Navigation:** In `SetupActivity.kt`, added a collector for the `isConnectedToPrimary` state to automatically navigate to `CaptureActivity` when the client connects.
    - [x] **Implement Explicit Disconnection Logic:**
        - [x] In `NetworkService.kt`, added a `LeaveGroup` message to the `Message` sealed class.
        - [x] In `NetworkService.kt`, created a `disconnectFromPrimary()` method that sends the `LeaveGrup` message to the server, closes the connection, and resets the `isConnectedToPrimary` state.
        - [x] On the server-side, updated the logic in `NetworkService.kt` to handle the `LeaveGroup` message by removing the disconnected client from its list of active connections.

- [x] **Task 2: Create a Role-Aware UI in `CaptureActivity`**
    - [x] **Determine Device Role:** In `CaptureActivity.kt`, modified the `onCreate()` method to check for a "client mode" `Intent` extra to determine the device's role.
    - [x] **Configure UI Based on Role:**
        - [x] Created a `setupUIForRole()` method to configure the UI elements based on the device's role.
        - [x] **If Client:** Enabled the camera preview but disabled all primary-specific controls. Repurposed the "Switch Mode" button into a "Disconnect" button that triggers the `disconnectFromPrimary()` method.
        - [x] **If Primary:** Maintained the existing UI and functionality.
    - [x] **Handle Disconnection from the Client UI:** In `CaptureActivity.kt`, while in client mode, observed the `isConnectedToPrimary` state. If the connection is lost, automatically `finish()` and return the user to the discovery screen (`SetupActivity`).
- [x] **Task 1: Correct Disconnection and Connection Logic**
    - [x] **Fix `disconnectFromPrimary`:** In `NetworkService.kt`, reverse the order of operations in the `disconnectFromPrimary` method so the `LeaveGroup` message is sent *before* the socket is closed.
    - [x] **Fix `connectToPrimary`:** In `NetworkService.kt`, move the `sendMessageToPrimary(Message.JoinGroup)` call to be the *first* action within the `connectToPrimary` method's `try` block.

- [x] **Task 2: Resolve UI Layout and Visibility Issues**
    - [x] **Adjust `activity_capture.xml`:** Modify the `activity_capture.xml` layout file to ensure the "Disconnect" button (`switchModeButton`) is always visible and not overlapped by other views. Ensure the client list and its header are hidden when in client mode.
    - [x] **Refine `setupUIForRole`:** In `CaptureActivity.kt`, refine the `setupUIForRole` method to ensure the correct UI elements are hidden or shown based on the device's role.
