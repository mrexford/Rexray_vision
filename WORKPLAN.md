# Rexray Vision - Architecture Refactor Plan

This document outlines the action plan for refactoring the application from a single-activity architecture to a more robust multi-activity, service-based architecture. This will address concurrency issues and improve maintainability.

## Phase 1: Foundational Service Implementation

-   [ ] **Create `NetworkService.kt`**:
    -   [ ] Define a new `NetworkService` that extends Android's `Service`.
    -   [ ] Implement it as a foreground service to ensure it remains active.

-   [ ] **Migrate Networking Logic to `NetworkService`**:
    -   [ ] Move all network-related code from `MainActivity` into `NetworkService`.
    -   [ ] Absorb the logic from the existing `NetworkManager` class into the service. The `NetworkManager` class should be deleted after migration.
    -   [ ] The service will be responsible for NSD (Network Service Discovery), `ServerSocket` management, and all client communication.

-   [ ] **Establish Service as the Single Source of Truth**:
    -   [ ] The `NetworkService` will own and manage all critical state, including the list of connected clients, camera settings, and capture state (e.g., `isArmed`, `isCapturing`).
    -   [ ] Expose this state to the UI layer using thread-safe observables like Kotlin's `StateFlow`.

-   [ ] **Implement Service Lifecycle Management**:
    -   [ ] Add logic to the Application's `onCreate` to start the `NetworkService`.
    -   [ ] Ensure the service is correctly started as a foreground service with a persistent notification.

## Phase 2: Activity Refactoring and Creation

-   [ ] **Create `CaptureActivity.kt`**:
    -   [ ] Create a new activity dedicated to the camera and capture UI.
    -   [ ] Move all camera-related UI elements and logic from `activity_main.xml` and `MainActivity.kt` to this new activity.

-   [ ] **Create `SetupActivity.kt`**:
    -   [ ] Create a new activity for role selection and device discovery.
    -   [ ] Move the relevant UI elements (role selection, discovery list) and logic from `MainActivity` to this new activity.

-   [ ] **Update `MainActivity.kt` to act as a dispatcher**:
    -   [ ] The original `MainActivity` will be repurposed as a simple, invisible dispatcher.
    -   [ ] On start, it will check the device's role and immediately launch either `SetupActivity` (for clients) or `CaptureActivity` (for primaries), and then `finish()` itself.

-   [ ] **Update `AndroidManifest.xml`**:
    -   [ ] Declare the new `NetworkService`, `CaptureActivity`, and `SetupActivity`.
    -   [ ] Set `MainActivity` as the `LAUNCHER` activity.

## Phase 3: Service-Activity Communication

-   [ ] **Implement Service Binding**:
    -   [ ] Both `CaptureActivity` and `SetupActivity` will bind to the `NetworkService` to communicate with it.
    -   [ ] Implement the `IBinder` interface in the `NetworkService` to provide a communication channel.

-   [ ] **Refactor `CaptureActivity` UI Logic**:
    -   [ ] Remove all direct state management.
    -   [ ] UI actions (e.g., pressing "Capture") will now send commands to the `NetworkService` (e.g., `service.startCapture()`).
    -   [ ] The UI will update its state by collecting the `StateFlow`s exposed by the `NetworkService`.

-   [ ] **Refactor `SetupActivity` UI Logic**:
    -   [ ] The "Discover" button will call a method on the bound `NetworkService` to initiate discovery.
    -   [ ] The list of available primary devices will be populated by observing a `StateFlow` of discovered services from the `NetworkService`.

## Phase 4: Synchronization, Safety, and Refinement

-   [ ] **Ensure Thread Safety**:
    -   [ ] Review all methods in `NetworkService` that modify state and ensure they are properly synchronized to prevent race conditions.
    -   [ ] Replace the standard list of clients with a thread-safe collection, such as `java.util.concurrent.CopyOnWriteArrayList`.

-   [ ] **Verify State Consistency**:
    -   [ ] Thoroughly test the application to ensure the UI in all activities consistently and correctly reflects the state from the `NetworkService`.
    -   [ ] Pay special attention to edge cases like device rotation, activities being destroyed and recreated by the OS, and rapid user input.

-   [ ] **Implement Robust Error Handling**:
    -   [ ] Add error handling for service binding failures.
    -   [ ] Ensure that network command failures (e.g., a client disconnects unexpectedly) are handled gracefully and the UI is updated accordingly.
