# HC_ServerAPI

REST API for executing server commands and monitoring server health from an external admin panel. Exposes an HTTP server with API key authentication that accepts command execution requests and returns server statistics including TPS, MSPT, memory, CPU, GC pauses, disk usage, network I/O, and per-world player/chunk counts.

## Features

- `POST /command` -- execute any server command remotely via JSON body (`{"command": "..."}`)
- `GET /commands` -- list all registered server commands with their arguments, types, descriptions, and subcommands
- `GET /health` -- unauthenticated health check endpoint
- `GET /stats` -- detailed server statistics: memory (heap), CPU load, uptime, player count, TPS, MSPT (10s/1m/5m averages, min/max), GC pause tracking, disk usage, network bytes in/out, and per-world info (players, chunks, tick)
- API key authentication via `X-API-Key` header
- Configurable port via `HC_API_PORT` environment variable (default: 7070)
- Configurable API key via `HC_API_KEY` environment variable

## Dependencies

None (standalone plugin with no external plugin dependencies).

## Building

```bash
./gradlew build
```
