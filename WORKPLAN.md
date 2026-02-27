# WORKPLAN

## FUTURE STATE (DO NOT IMPLEMENT NOW)
- **WARNING**: This is a major architectural shift. Proceeding with this requires a full refactor of state master-ship from `CaptureActivity` to `NetworkService`.
- **Goal**: Ensure the TCP server and session state persist during backgrounding, with the camera hardware being the only component released/reacquired.

### Phase 1: Documentation & State Master-ship
1. **Update `CODEBASE_OVERVIEW.md`**: Document the new state-management architecture where `NetworkService` is the source of truth and its decomposition into `RexrayServer`/`RexrayClient` helpers.
2. Update `NetworkService.kt` to manage its own operational state:
   - Add `ServiceRole` enum (IDLE, PRIMARY, CLIENT).
   - Move `isArmed`, `iso`, `shutterSpeed`, `captureRate`, `captureLimit`, and `projectName` into `NetworkService`.
   - Make `registerService` and `connectToPrimary` idempotent.
3. Update `CaptureActivity.kt` to synchronize with `NetworkService` state:
   - Remove redundant server-start logic from `onServiceConnected`.
   - Implement `StateFlow` observation to update UI when Service state changes.

### Phase 2: Structural Decomposition (Maintain Low Complexity)
1. Extract Server-side socket handling and client management into a helper class (e.g., `RexrayServer`) to keep `NetworkService.kt` below 300 lines.
2. Extract Client-side socket and message listening logic into a helper class (e.g., `RexrayClient`).
3. Refactor `NetworkService.kt` to delegate communication tasks to these helpers while remaining the central state authority.

### Phase 3: Hardware Lifecycle Integration
1. Update `BaseCaptureFragment.kt` for graceful camera lifecycle:
   - Ensure camera is closed asynchronously in `onStop`.
   - Re-initialize camera and apply current `NetworkService` parameters in `onStart`.
2. Build and verify background/foreground persistence across multi-device rig.

## CURRENT SIDEQUEST
- Waiting for user instruction.
