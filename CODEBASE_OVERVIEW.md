# Codebase Overview

This document provides a high-level overview of the Rexray Vision application's architecture and key components.

## Project Structure

The project is a standard Android application with a single `:app` module.

## Core Components

*   **`SetupActivity`**: **The Primary Entry Point.** Allows the user to select the device's role: Primary or Client. It handles the initial project creation, network discovery, and connection setup.
*   **`CaptureActivity`**: The main screen for the capture phase, responsible for managing fragments and synchronizing UI with the `NetworkService`.
*   **`BaseCaptureFragment`**: A fragment that encapsulates core camera hardware logic, including session management and image capture. It is designed to be lifecycle-aware, releasing camera hardware when backgrounded.
*   **`RexrayCameraManager`**: A class that wraps the Camera2 API, simplifying camera setup and configuration.
*   **`CameraSessionManager`**: Manages the `CameraCaptureSession` and handles capture requests.
*   **`CaptureStateHandler`**: Manages the state of image capture, including pending buffers and results. Its buffer handling is designed to correctly handle row-stride in image data to prevent crashes.
*   **`ImageSaver`**: A background component that saves captured RAW images to the app's internal cache as DNG files.
*   **`FileMigrationService`**: A foreground service responsible for moving saved DNG files from internal cache to public storage.
*   **`NetworkService`**: **The System Source of Truth.** A persistent foreground service that maintains the network server (Primary) or client connection (Client) and holds the global session state (Armed status, ISO, Shutter Speed, etc.). It persists across Activity backgrounding.
    *   **`RexrayServer`**: (Internal to Service) Handles the TCP server socket, client connections, and broadcasting.
    *   **`RexrayClient`**: (Internal to Service) Handles the client socket and incoming message listening.
*   **`ExposureAnalysisStrategy`**: An interface for different exposure analysis algorithms.
*   **`ByteBufferPool`**: A utility class for pooling and reusing `ByteBuffer`s.

## Architectural Patterns

*   **Service-Centric State**: The `NetworkService` acts as the master authority for the capture session. Activities and Fragments observe `StateFlow`s from the service to remain synchronized.
*   **Producer-Consumer**: The `BaseCaptureFragment` produces image data; `ImageSaver` consumes it.
*   **Lifecycle-Aware Hardware**: Hardware resources (Camera) are released during backgrounding to ensure system stability, while network resources (Sockets) are maintained by the foreground service.
