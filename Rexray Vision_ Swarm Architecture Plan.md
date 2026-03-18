# ***Rexray\_vision: Synchronized Swarm Architecture & Implementation Plan v2.0***

*This blueprint defines the transition to a high-performance, distributed capture rig. It prioritizes microsecond-level synchronization, internal storage I/O isolation, and a modular system design for professional photogrammetry.*

---

## ***1\. System Architecture Overview***

*The system utilizes a Master-Client (Brain-Node) topology:*

* ***The Brain (Pixel 9 Pro):** Functions as the SLAM/ARCore anchor source, the PTP (Precision Time Protocol) master clock, and the global command coordinator.*  
* ***The Nodes (Swarm):** Function as high-speed capture buffers that synchronize internal monotonic clocks to the Brain.*  
  ---

  ## ***2\. Calibration Workflow: The "Calibration Flip"***

*To ensure the Brain can establish spatial anchors without hardware contention, the system uses two distinct states managed by the **Arm/Disarm** logic:*

* ***Disarmed (Setup Mode):***  
  * *The Brain runs a standard viewfinder with manual controls (ISO, Shutter, WB).*  
  * *`CameraSettingsManager.kt` broadcasts these parameters to all Nodes in real-time.*  
* ***Armed (Capture Mode):***  
  * *Standard camera sessions are destroyed to free ISP resources.*  
  * *`ARCore` and `ImuLogger` are initialized.*  
  * *The UI displays the AR Spatial Map for the SLAM "sweep."*  
  * *Nodes receive a `LOCK_AND_PREPARE` command to hold settings and pre-allocate storage handles.*

  ---

  ## ***3\. Local Testing & Standalone Sandbox***

*To facilitate debugging without a distributed network, the following modules are included:*

* ***Lens Explorer:** A manual interface to enumerate and select specific physical camera IDs (e.g., Physical 0 for Wide, Physical 2 for Ultrawide) to verify lens specs and FOV.*  
* ***SLAM Lab:** A standalone mode utilizing the ARCore Recording API to save SLAM/IMU sessions locally for offline verification.*  
  ---

  ## ***4\. Engineering Blueprint (Build Order)***

  ### ***Phase 0: Hardware Discovery & Local Testing***

| *File Location* | *Component* | *Primary Functions* |
| :---- | :---- | :---- |
| *`com.rexray.vision.camera`* | *`LensScanner.kt`* | *`enumeratePhysicalCameras()`, `getPhysicalLensSpecs()`* |
| *`com.rexray.vision.ui`* | *`TestingActivity.kt`* | *`testLocalBurst()`, `switchPhysicalLens()`* |

  *Export to Sheets*

***Implementation Mechanism:***

* ***Lens Discovery:** Query `getPhysicalCameraIds()` via `CameraCharacteristics`.*  
* ***Manual Selection:** The UI provides a toggle to force a specific Physical ID, ensuring the app pulls from the correct raw sensor during calibration.*

  ### ***Phase 1: The Timing Backbone (Microsecond Synchronization)***

| *File Location* | *Component* | *Primary Functions* |
| :---- | :---- | :---- |
| *`com.rexray.vision.sync`* | *`TimeSyncEngine.kt`* | *`calculateClockOffset()`, `getSynchronizedTime()`* |
| *`com.rexray.vision.sync`* | *`NetworkTimeProvider.kt`* | *`manageUdpHandshake()`, `onSyncPulseReceived()`* |

  *Export to Sheets*

***Implementation Mechanism:***

* ***Clock Source:** Use `SystemClock.elapsedRealtimeNanos()` (Monotonic `BOOTTIME`) exclusively to avoid wall-clock jumps.*  
* ***RTT Compensation:** The Brain sends a UDP multicast "Pulse A"; Nodes respond with "Pulse B". The Brain calculates Round Trip Time (RTT) and broadcasts the final offset.*

  ### ***Phase 2: The Command & State Machine (Calibration Flip)***

| *File Location* | *Component* | *Primary Functions* |
| :---- | :---- | :---- |
| *`com.rexray.vision.state`* | *`WorkflowManager.kt`* | *`transitionToArmed()`, `teardownSetupCamera()`* |
| *`com.rexray.vision.network`* | *`SwarmProtocol.kt`* | *`broadcastSettings()`, `handleStateChange()`* |

  *Export to Sheets*

***Implementation Mechanism:***

* ***State Transition:** Upon "Arming," the app explicitly closes the setup camera session before starting the `ArSession` to prevent "Camera in Use" errors.*  
* ***Broadcast Logic:** `CameraSettingsManager.kt` triggers a socket broadcast for every parameter change during the "Disarmed" state.*

  ### ***Phase 3: Dual-Stream Engine (Internal Storage Capture)***

| *File Location* | *Component* | *Primary Functions* |
| :---- | :---- | :---- |
| *`com.rexray.vision.camera`* | *`DualCameraManager.kt`* | *`openPhysicalCameras()`, `createBurstSession()`* |
| *`com.rexray.vision.io`* | *`InternalStorageManager.kt`* | *`saveImageToInternal()`, `prepareStorageHandles()`* |

  *Export to Sheets*

***Implementation Mechanism:***

* ***Capture Path:** Write JPEG bytes directly to the app's internal storage using `FileOutputStream`. This avoids `MediaStore` thumbnailing overhead during the 10-second burst.*  
* ***Naming:** `NamingManager.kt` generates filenames based on the microsecond `SENSOR_TIMESTAMP` (e.g., `IMG_[timestamp]_WIDE.jpg`).*

  ### ***Phase 4: Spatial & Sensor Fusion***

| *File Location* | *Component* | *Primary Functions* |
| :---- | :---- | :---- |
| *`com.rexray.vision.sensors`* | *`ImuPacketizer.kt`* | *`interpolateSamples()`, `startHighFreqLogging()`* |
| *`com.rexray.vision.spatial`* | *`AnchorEngine.kt`* | *`generateArAnchor()`, `exportPointCloud()`* |

  *Export to Sheets*

***Implementation Mechanism:***

* ***IMU Interpolation:** Aligns 200Hz IMU samples to the hardware `SENSOR_TIMESTAMP` midpoint of each camera frame.*  
* ***ARCore Anchor:** The Brain establishes a global origin; all spatial data is exported relative to this coordinate in `.ply` format.*

  ### ***Phase 5: MediaStore Processing & Export***

| *File Location* | *Component* | *Primary Functions* |
| :---- | :---- | :---- |
| *`com.rexray.vision.export`* | *`MediaStoreExporter.kt`* | *`processInternalToMediaStore()`, `scanNewFiles()`* |
| *`com.rexray.vision.export`* | *`OffloadWorker.kt`* | *`compileSessionPackage()`, `verifyDataIntegrity()`* |

  *Export to Sheets*

***Implementation Mechanism:***

* ***Post-Capture Sync:** Once the burst ends, `MediaStoreExporter.kt` moves files to the public MediaStore and triggers a system scan.*  
* ***Metadata Injection:** `MetadataPacker.kt` injects microsecond timestamps into the JPEG `UserComment` headers before finalizing the session package.*  
  ---

  ## ***5\. Engineering Consistency Audit***

1. ***I/O Isolation:** Capturing to internal storage eliminates thumbnailing and indexing as potential causes of dropped frames during 15fps dual-streaming.*  
2. ***Clock Source:** All timestamps (Camera/IMU) derive from the same monotonic hardware clock, ensuring zero temporal drift.*  
3. ***Modular Logic:** Extraction of settings and naming logic prevents UI thread jank during high-speed capture.*  
   ---

   ## ***6\. Final Execution Path***

1. ***Initialize Phase 0** to confirm physical lens access and local testing.*  
2. ***Build Phase 1** to establish the microsecond timing foundation.*  
3. ***Implement Phase 2 & 3** to finalize the "Calibration Flip" and internal storage capture.*  
4. ***Finalize Phase 4 & 5** for spatial data fusion and MediaStore export.*  
   

