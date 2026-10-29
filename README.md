# Akashic World Engine

Server-authoritative AI TRPG world simulation, designed for Kotlin/Ktor, PostgreSQL and Godot clients.

> **Status: initial public repository scaffold — not a deployed or validated backend.** This repository is intentionally public at present. Do not commit credentials, real lore documents, user data or private NPC secrets.

## Architecture contract (v1)

- Godot sends JSON commands; the World Engine validates and commits state changes.
- One isolated World Instance per player or party; server owns world time and truth.
- Hybrid clock: interaction, continuous and fast-forward modes.
- Append-only event log, state projections, deterministic replay and idempotent commands.
- LLM-generated text is never trusted as authoritative state; client ChatGPT tokens never reach the server.
- Actor-visible responses must exclude private NPC beliefs and undiscovered lore.
- PostgreSQL transactions must atomically update events, state, schedules and idempotency.

## Intended deployment

- Ktor server on Render, Singapore region; external Neon PostgreSQL 17 database `akashic`.
- Godot web client on a separate Cloudflare Pages project.
- Production OIDC required; development authentication MUST NOT be publicly reachable.

## Next steps

The full local reference package has not yet been uploaded. Run the GitHub Actions verification after adding the Kotlin sources, Dockerfile, Flyway migrations and contract tests. Do not deploy until Gradle build, PostgreSQL integration and authentication checks pass.

Source package: `akashic-world-engine-render-ready-2026-10-29.zip` (local handoff; never commit credentials).

