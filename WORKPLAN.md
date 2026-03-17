# Workplan - Flash Intensity Debugging and Optimization

Investigate why the flash intensity slider is not changing the hardware brightness and resolve the CAMERA_IN_USE conflict using API 34+ FLASH_STRENGTH_LEVEL.

## Tasks
- [X] Analyze Logcat for flash errors (DONE: Found CAMERA_IN_USE conflict)
- [X] Implement `CaptureRequest.FLASH_STRENGTH_LEVEL` in `CameraSessionManager.kt` for variable intensity during session (DONE)
- [X] Remove redundant/failing `CameraManager` torch calls from `CaptureActivity.kt` (DONE)
- [X] Verify build and test flash intensity slider on device (DONE)
- [X] Adjust scaling logic if `maxLevel` is low or fixed (DONE: Verified current logic is optimal for Pixel 6+)
