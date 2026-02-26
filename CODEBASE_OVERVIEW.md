# Codebase Overview

This document provides a high-level overview of the Rexray Vision application's architecture and key components.

## Project Structure

The project is a standard Android application with a single `:app` module.

## Core Components

*   **`MainActivity`**: The application's entry point. Directs the user to `SetupActivity`.
*   **`SetupActivity`**: Allows the user to select the device's role: Primary or Client. It handles the initial setup and, for Client devices, the connection to a Primary.
*   **`CaptureActivity`**: The main screen for the capture phase, responsible for managing the camera preview, user interface, and capture lifecycle.
*   **`BaseCaptureFragment`**: A headless fragment that encapsulates the core camera logic, including session management, image capture, and exposure analysis. The auto-exposure logic uses a passive analysis of the preview stream's histogram data to continuously adjust the ISO.
*   **`RexrayCameraManager`**: A class that wraps the Camera2 API, simplifying camera setup and configuration.
*   **`CameraSessionManager`**: Manages the `CameraCaptureSession` and handles capture requests.
*   **`CaptureStateHandler`**: Manages the state of image capture, including pending buffers and results. Its buffer handling is designed to correctly handle row-stride in image data to prevent crashes.
*   **`ImageSaver`**: A background service that saves captured RAW images to disk as DNG files using `DngCreator`. It acts as the consumer in a producer-consumer pattern.
*   **`ExposureAnalysisStrategy`**: An interface for different exposure analysis algorithms. The current implementation is `HistogramEttrAnalysisStrategy`, which uses a histogram-based approach to determine the correct exposure.
*   **`ByteBufferPool`**: A utility class for pooling and reusing `ByteBuffer`s to reduce memory allocation and garbage collection overhead during high-speed capture.
*   **`NetworkService`**: A foreground service for network discovery and communication between primary and client devices.

## Architectural Patterns

*   **Producer-Consumer**: The `BaseCaptureFragment` acts as the producer of image data, and the `ImageSaver` is the consumer.
*   **StateFlow**: Used to propagate state and asynchronous updates from backend services to the UI.
*   **Dependency Injection**: While not using a formal framework, dependencies are manually provided to classes.
