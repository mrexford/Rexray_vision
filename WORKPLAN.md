# Rexray Vision - Active Work Plan

This document outlines the active and in-review tasks for the project.

## In Progress

- **Task 1: Optimize Server Thread Handling**
    - **Replace Executor:** In `NetworkService.kt`, replace the `Executors.newCachedThreadPool()` with a more efficient `Executors.newFixedThreadPool(numCores * 2)` to prevent resource exhaustion and improve scalability.

- **Task 2: Implement Periodic Client Status Updates**
    - **Create Status Job:** In `NetworkService.kt`, create a repeating background job to run every 2 seconds on the client.
    - **Gather Status:** In `CaptureActivity.kt`, create functions to get battery level, storage space, and the current image count.
    - **Send Status:** The background job will collect the status and send it to the server in a `StatusUpdate` message.
    - **Process Status:** On the server, handle the incoming `StatusUpdate` message and update the `_connectedClients` state flow.

- **Task 3: Implement Command Acknowledgement (ACK) System**
    - **Update Protocol:** Add a unique `commandId: String` to all server-to-client commands and create a new `CommandAck(commandId: String)` message.
    - **Client-Side ACK:** When a client receives and processes a command, it will send a `CommandAck` message back to the server.
    - **Server-Side Tracking:** Add a `lastCommandAck: Boolean` property to the `ClientStatus` data class. When a command is sent, set this to `false` for all clients. When an ACK is received, set it to `true`.
    - **Update UI:** Modify the `ClientStatusAdapter` to visually indicate the acknowledgement status of each client.

## In Review

- *No tasks in review.*

## Completed

- *All completed tasks have been moved to `COMPLETED_WORK.md`.*

## Future Tasks

- *No future tasks planned.*
