# Rexray Vision - Completed Work

This document archives all completed tasks for the project.

- [x] **Task 1: Resolve Lifecycle and Threading Issues**
    - **Symptom:** Critical `RejectedExecutionException` in the finalizer and `IllegalStateException` for messages to a dead thread, causing instability and crashes.
    - **Analysis:** The root cause is a race condition between the `Activity` lifecycle and background threads. The `onStop()` method terminates executors before dependent operations (like camera finalization) have completed.
    - **Action:** Overhaul the threading model in `CaptureActivity.kt` to be lifecycle-aware. Enforce a strict, ordered shutdown of the camera session and its executors within `onStop()` to prevent race conditions.

- [x] **Task 2: Fix `CommandAck` Failure and Stale Connection Handling**
    - **Symptom:** Client fails to send `CommandAck` messages, and the server does not reject stale connections, leading to a desynchronized state.
    - **Analysis:** The lifecycle crashes from Task 1 are preventing the `CommandAck` from being sent. Additionally, the client does not gracefully handle the server's rejection of a stale `projectName`.
    - **Action:** 
        1. The fix for Task 1 will stabilize the client, allowing `CommandAck` messages to be sent reliably.
        2. Implement client-side logic in `NetworkService.kt` to handle the `ConnectionRejected` message, forcing a re-discovery when a connection is refused due to a stale name.

- [x] **Task 3: Fix Camera Resource Leaks**
    - **Symptom:** Logs show `A resource failed to call Surface.release`.
    - **Analysis:** `ImageReader` surfaces are not being closed when the camera is shut down.
    - **Action:** Modify `RexrayCameraManager.kt` to explicitly close `rawImageReader` and `analysisImageReader` in the `closeCamera()` method.

- [x] **Task 4: Ensure Consistent FPS Setting Propagation**
    - **Symptom:** The capture rate (FPS) setting is not consistently updated on client devices.
    - **Analysis:** The `SetParams` message is only broadcast when a change to `captureRate` also forces a change to `shutterSpeed`.
    - **Action:** In `CaptureActivity.kt`, modify the `captureRateSeekBar` listener to unconditionally broadcast a `SetParams` message whenever the user changes the value.

- [x] **Task 1: Resolve Lifecycle and Threading Issues**
    - [x] **Symptom:** Logs show `IllegalStateException: ... message to a Handler on a dead thread` and `Skipped 52 frames! ... too much work on its main thread`.
    - [x] **Analysis:** Critical errors in background thread management and UI updates are causing instability and performance degradation, leading to UI freezes and missed state updates.
    - [x] **Action:** Refactor thread and lifecycle management in `CaptureActivity.kt`. Ensure all camera and network operations are off the main thread, callbacks are handled safely, and all executors and threads are properly shut down.

- [x] **Task 2: Fix Camera Resource Leaks**
    - [x] **Symptom:** Logs show `A resource failed to call Surface.release`.
    - [x] **Analysis:** `Surface` objects from the camera's `ImageReader` are not being released correctly, leading to memory leaks and camera hardware failures.
    - [x] **Action:** Modify `RexrayCameraManager.kt` to ensure `rawImageReader` and `analysisImageReader` are explicitly closed when the camera is shut down.

- [x] **Task 3: Fix Stale Discovery and Connection Logic**
    - [x] **Symptom:** A client with a stale project name connects, but its status updates are not reflected on the server UI.
    - [x] **Analysis:** The server accepts connections from clients with out-of-date service information, leading to a desynchronized state where the server cannot correctly process the client's messages.
    - [x] **Action:** In `NetworkService.kt`, add a check within the `JoinGroup` message handler. The server will compare the `projectName` from the client's service info with its own. If they do not match, the connection will be rejected, forcing the client to re-discover.

- [x] **Task 4: Ensure Consistent FPS Setting Propagation**
    - [x] **Symptom:** The capture rate (FPS) setting is not consistently updated on client devices.
    - [x] **Analysis:** The `SetParams` message is only broadcast when a change to `captureRate` also forces a change to `shutterSpeed`.
    - [x] **Action:** In `CaptureActivity.kt`, modify the `captureRateSeekBar` listener to unconditionally broadcast a `SetParams` message with the updated settings whenever the user changes the value.

- [x] **Task 1: Implement Auto-Discovery on Mode Switch**
    - [x] **Goal:** Automatically start network discovery when the user switches from Primary to Client mode.
    - [x] **Action 1:** In `CaptureActivity.kt`, add an `autoDiscover` boolean extra to the `Intent` that launches `SetupActivity`.
    - [x] **Action 2:** In `SetupActivity.kt`, check for the `autoDiscover` extra in `onCreate()` and trigger discovery if it's `true`.

- [x] **Task 1: Fix Client-to-Primary Command Injection Vulnerability**
    - [x] **Analyze Vulnerability:** A client can send commands (e.g., `ArmCapture`) that are incorrectly processed by the primary, leading to unintended behavior.
    - [x] **Whitelist Messages:** In `NetworkService.kt`, modify the `handleClient` function to only process expected client-side messages (`JoinGroup`, `LeaveGroup`, `StatusUpdate`, `CommandAck`, `UpdateCameraName`).
    - [x] **Discard Others:** All other unexpected message types will be logged as errors and discarded.

- [x] **Task 2: Resolve State Variable Race Condition**
    - [x] **Identify Race Condition:** In `CaptureActivity.kt`, state variables like `isArmed` are accessed from both the UI and background threads without synchronization.
    - [x] **Implement Atomic State:** Convert `isArmed`, `isCapturing`, and `isAnalyzing` to `AtomicBoolean` to ensure thread-safe read/write operations.

- [x] **Task 1: Fix Crash on Command Acknowledgement**
    - [x] **Identify Cause:** Analyzed logcat and identified `IllegalArgumentException` in `MessageTypeAdapterFactory.kt`.
    - [x] **Update Adapter:** Added `CommandAck` to the `when` statement in the `read` function of the `MessageTypeAdapterFactory`.
    - [x] **Verify Fix:** Built the project successfully.

- [x] **Task 1: Optimize Server Thread Handling**
    - [x] **Replace Executor:** In `NetworkService.kt`, replace the `Executors.newCachedThreadPool()` with a more efficient `Executors.newFixedThreadPool(numCores * 2)` to prevent resource exhaustion and improve scalability.

- [x] **Task 2: Implement Periodic Client Status Updates**
    - [x] **Create Status Job:** In `NetworkService.kt`, create a repeating background job to run every 2 seconds on the client.
    - [x] **Gather Status:** In `CaptureActivity.kt`, provide the `imageCount` and `isArmed` status to the `NetworkService`.
    - [x] **Send Status:** The background job will collect the status and send it to the server in a `StatusUpdate` message.
    - [x] **Process Status:** On the server, handle the incoming `StatusUpdate` message and update the `_connectedClients` state flow.

- [x] **Task 3: Implement Command Acknowledgement (ACK) System**
    - [x] **Update Protocol:** Add a unique `commandId: String` to all server-to-client commands and create a new `CommandAck(commandId: String)` message.
    - [x] **Client-Side ACK:** When a client receives and processes a command, it will send a `CommandAck` message back to the server.
    - [x] **Server-Side Tracking:** Add a `lastCommandAck: Boolean` property to the `ClientStatus` data class. When a command is sent, set this to `false` for all clients. When an ACK is received, set it to `true`.
    - [x] **Update UI:** Modify the `ClientStatusAdapter` to visually indicate the acknowledgement status of each client.

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
- [x] **Task 1: Implement `StopCapture` Command**
    - **Goal:** Ensure all clients stop capturing the instant the primary device does.
    - **Action:**
        1. In `NetworkService.kt`, add a `StopCapture(commandId: String)` message to the `Message` protocol.
        2. In `CaptureActivity.kt`, modify `stopBurstCapture()` on the primary device to broadcast this new `StopCapture` message.
        3. In `CaptureActivity.kt`, update the client-side `incomingMessages` collector to handle the `StopCapture` message, call the local `stopBurstCapture()` function, and send a `CommandAck` message back.
- [x] **Task 2: Centralize Settings Synchronization**
    - **Goal:** Make settings synchronization robust and eliminate inconsistent behavior.
    - **Action:**
        1. In `CaptureActivity.kt`, create a new private `broadcastSettings()` function that constructs and broadcasts the `SetParams` message with all four current settings (ISO, shutter speed, capture rate, and capture limit).
        2. Modify the `onProgressChanged` listeners for all four settings `SeekBar` controls. Each listener will now call the new `broadcastSettings()` function whenever its value changes, ensuring any adjustment is propagated immediately and consistently.
- [x] **Task 3: Implement Dynamic, Event-Driven Status Updates**
    - **Goal:** Provide immediate UI feedback for critical actions and maintain an efficient, dynamic polling rate for background status.
    - **Action:**
        1. **Event-Driven Updates:** In `CaptureActivity.kt`, add logic to the client-side handlers for `ArmCapture`, `DisarmCapture`, and `StartCapture` (and the new `StopCapture`) to immediately send a one-off `StatusUpdate` message back to the primary the moment a command is executed.
        2. **Dynamic Polling Rate:** In `NetworkService.kt`, the status update scheduler will be modified. It will use a 250ms interval when the client is armed and a 2000ms interval when it is disarmed. This will be achieved by canceling and rescheduling the repeating task whenever the client's armed state changes. This provides frequent updates on image counts during a capture while reducing network traffic during idle periods.
- [x] **Task 4: Fix Client-Side Deserialization Crash**
    - **Goal:** Prevent the client from crashing when it receives a `StopCapture` message.
    - **Action:** In `MessageTypeAdapterFactory.kt`, add the `StopCapture` message type to the `when` statement in the `read` function.
- [x] **Task 5: Refactor Client Management to Support Multiple Connections from a Single IP**
    - **Goal:** Correct the flawed client management logic that uses the IP address as a unique key, preventing multiple clients on the same IP from being managed correctly.
    - **Action:**
        1. In `NetworkService.kt`, change the key of the `_connectedClients` map from `String` to `java.net.Socket`.
        2. Update all functions that interact with this map to use the `Socket` object for all lookups, insertions, and deletions.
