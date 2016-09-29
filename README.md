# Back-pressured processmanager example

An example applying backpressure to ProcessManager that execute long running process to prevent process overflow.

This documentation status is work in progress.

For japanese reader, 
日本語のドキュメントは[ここ](https://github.com/TanUkkii007/backpressured-processmanager-example/blob/master/README_ja.md)

## Problem

Process manager is one of the enterprise integration pattern that manages long running processes.
Process manager usually has multiple process steps so can be implemented with state machine, for example, Persistent FSM.

## 