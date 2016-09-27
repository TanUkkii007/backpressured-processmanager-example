# Back-pressured processmanager example

example applying backpressure to ProcessManager that execute long running process to prevent process overflow

## Problem

Process manager is one of the DDD implementation pattern that manages long running processes.
Process manager usually has multiple process steps so can be implemented with state machine, for example, Persistent FSM.

## 