# Codebase Overview

This document provides a high-level overview of the Rexray Vision application's architecture and key components.

## Project Structure

The project is a standard Android application with a single `:app` module.

## Core Swarm Components

*   **`NetworkService`**: **The System Source of Truth.** A persistent foreground service maintaining the PTP-like synchronization master/node status and global session parameters.
    *   **`TimeSyncEngine`**: Calculates microsecond-level clock offsets between Brain and Nodes.
    *   **`NetworkTimeProvider`**: UDP-based synchronization engine for RTT compensation.
*   **`CaptureActivity`**: The central coordinator for the "Calibration Flip". It manages the transition between setup and high-speed capture.
*   **`WorkflowManager`**: Implements the state machine for `ARMED` and `DISARMED` system states.
*   **`DualCameraManager`**: A high-performance engine for simultaneous physical camera capture, bypassing the standard Android camera preview pipeline for microsecond precision.
*   **`InternalStorageManager`**: Manages low-latency I/O to the app's internal filesystem to avoid `MediaStore` thumbnailing overhead during burst capture.
*   **`AnchorEngine`**: ARCore-based engine for establishing spatial anchors and exporting point clouds in `.ply` format.
*   **`ImuPacketizer`**: High-frequency (200Hz+) inertial sensor logger for temporal sensor fusion.
*   **`MetadataPacker`**: Injects PTP-synchronized timestamps into JPEG EXIF `UserComment` headers.
*   **`OffloadWorker`**: A background task that packages images, IMU data, and spatial point clouds into a single session ZIP for offloading.

## Legacy & Setup Components

*   **`SetupActivity`**: Handles network discovery and role selection (Primary/Client).
*   **`BaseCaptureFragment`**: Used for the "Disarmed" state viewfinder and initial parameter adjustment.
*   **`RexrayCameraManager` / `CameraSessionManager`**: Wrappers for the Camera2 API used during the setup phase.
*   **`ImageSaver`**: Background component for saving RAW/JPEG images during standard capture.
*   **`FileMigrationService`**: A foreground service that moves captured data from internal storage to public storage post-session.

## Architectural Patterns

*   **Calibration Flip**: The system explicitly tears down setup camera sessions to free up ISP bandwidth for ARCore and high-speed multi-lens capture during the "Armed" phase.
*   **Monotonic Timing Backbone**: All sensor data (Camera, IMU, ARCore) is indexed using a shared monotonic clock synchronized via UDP to the Primary device.
*   **I/O Isolation**: Using internal storage during capture and deferred migration to `MediaStore` ensures zero dropped frames at 15fps+ dual-streaming.
*   **Service-Authoritative State**: The `NetworkService` remains the master authority for session state, persisting across UI lifecycle changes.
