# Rexray Vision - Pre-Alpha Release Notes

## Project Overview

Rexray Vision is a specialized Android application designed for high-speed photogrammetry. The primary goal is to capture RAW (DNG) images at a rate exceeding 10 frames per second on Google Pixel 6 and newer devices. This pre-alpha release establishes the foundational components of the application, focusing on high-performance RAW image capture and storage.

## Core Functionality

### High-Speed RAW Capture

The application leverages the Camera2 API to achieve high-speed RAW image capture. This is crucial for photogrammetry, where capturing a rapid sequence of images is necessary to reconstruct a 3D model.

*   **Camera2 API:** Direct access to the camera hardware is managed through the Camera2 API, providing the low-level control required for high-frequency RAW image bursts.
*   **Producer-Consumer Architecture:** A producer-consumer pattern is implemented to handle the high data rate from the camera sensor. The "producer" (camera) captures RAW frames and places them into a memory buffer. The "consumer" (storage writer) then retrieves these frames and saves them to disk. This decouples the capture and storage processes, preventing backpressure on the camera and minimizing frame drops.
*   **RAM-Based Burst Buffer:** A `BlockingQueue` is used as a RAM-based buffer. This in-memory queue temporarily holds the RAW image data, allowing the camera to continue capturing frames even if the storage I/O is momentarily slow.
*   **Zero-Copy Buffers:** To minimize garbage collection (GC) pressure and improve performance, the application uses `ByteBuffer.allocateDirect()`. This allocates memory outside of the Java heap, enabling the system to pass data between the camera, application, and storage without creating unnecessary copies.

### Storage

*   **DNG File Format:** Images are saved in the DNG (Digital Negative) format, a lossless raw image format that preserves the full sensor data.
*   **File Naming Convention:** Captured images are named according to the following convention: `[ProjectName]_[CameraName]_[Timestamp].dng`.

## User Interface

The current user interface is minimal, providing basic controls to start and stop the image capture process. The focus of this pre-alpha release has been on the underlying capture and storage pipeline.

*   **`activity_main.xml`:** The main layout file defines the user interface, which includes a button to initiate the capture sequence.
*   **`MainActivity.kt`:** This is the primary activity of the application. It handles user interaction, permissions, and orchestrates the camera and storage components.

## Project Structure

The project is organized as a standard Android application:

*   **`app` module:** Contains the application code, resources, and `build.gradle.kts`.
*   **`gradle` directory:** Contains the Gradle wrapper files.
*   **`build.gradle.kts` (project level):** The main build script for the project.
*   **`settings.gradle.kts`:** Configures the project's modules.

## How to Build and Run

1.  Open the project in Android Studio.
2.  Connect a Google Pixel 6 or newer device with USB debugging enabled.
3.  Build and run the `app` module.
4.  Grant camera and storage permissions when prompted.
5.  Press the capture button to begin recording RAW images.

## Future Development

This pre-alpha release is the first step towards a fully-featured photogrammetry rig. Future development will focus on:

*   **Network Synchronization:** Implementing a mechanism to synchronize the start and stop times of multiple devices over a local network.
*   **Background Upload:** Adding support for automatically uploading captured images to a local network store (SMB/NAS).
*   **UI Enhancements:** Improving the user interface with real-time feedback, image previews, and more advanced controls.
*   **Error Handling and Stability:** Improving the robustness of the application to handle various error conditions and ensure stable operation over long capture sessions.
