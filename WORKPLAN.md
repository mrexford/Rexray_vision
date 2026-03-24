# Workplan: ARCore Depth Map Initialization & Stability Fixes (Revised)

This plan addresses the "pure green" visualization, "Unsupported Depth Mode" warnings, and persistent "surface abandoned" errors.

## Phase 1: Depth Visualization & Configuration
- [ ] **PointRenderer.kt:** 
    - Update `depthFragmentShaderCode` to handle zero-depth values (transparency) and use a Turbo-like colormap.
    - Verify and correct texture sampling for ARCore 16-bit depth maps.
- [ ] **AnchorEngine.kt:** 
    - Change default depth mode to `Config.DepthMode.AUTOMATIC` to ensure hardware compatibility.
    - Implement `RAW_DEPTH_ONLY` as a conditional override only if supported.
    - Ensure `arSession.setCameraTextureNames` correctly maps textures for the active mode.

## Phase 2: Robust Surface & Session Transitions
- [ ] **CaptureActivity.kt:** 
    - Increase the teardown delay to 500ms in `onDisarmCapture`.
    - Implement explicit state handling to prevent overlapping Camera2/ARCore sessions.
- [ ] **BaseCaptureFragment.kt:** 
    - Modify `startPreview()` to wait for a fresh `onSurfaceTextureAvailable` callback if the current surface is invalid or abandoned.
- [ ] **WorkflowManager.kt:** 
    - Add `AppState.TEARDOWN` to manage the transition gap between AR and Standard Camera modes.

## Phase 3: Hardware Fallbacks & Verification
- [ ] **AnchorEngine.kt:** 
    - Implement a CPU-to-GPU texture upload path for depth frames as a fallback if automatic binding fails.
    - Add explicit logging for `Session.isDepthModeSupported` results.

## Phase 4: Final Validation
- [ ] **Logcat Check:** Confirm "Unsupported Depth Mode" warning is resolved.
- [ ] **UI Check:** Verify depth heatmap is visible (not solid green) when AR is READY.
- [ ] **Stability Check:** Cycle ARM/DISARM 5 times to ensure no "Surface was abandoned" crashes.
