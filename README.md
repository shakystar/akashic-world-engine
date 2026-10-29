# Akashic World Engine

Server-authoritative AI TRPG world simulation prototype.

**Repository visibility:** PUBLIC (temporary, at owner's request). No credentials, private world lore or real player data should be committed.

**Status:** Kotlin/Ktor + PostgreSQL reference implementation uploaded, with real build and DB tests still pending. Not deployed.

## Technology

- Kotlin/JDK 21, Ktor, Gradle 9.1
- PostgreSQL 17 + Flyway, JDBC/Hikari
- Docker multi-stage image
- Render development-only Blueprint (`render.yaml`), auto-deploy disabled
- GitHub Actions with PostgreSQL integration test

## World model

Each player owns an isolated World Instance. The server alone determines valid actions and world-time progression. Clients submit JSON commands and receive sanitized, actor-visible events. LLM narration and generated images may enhance presentation but never change the authoritative state.

The initial fixture supports `dialogue.speak`, `character.move`, `world.inspect`, `character.rest`; two locations and two NPCs. It is not the final lore or a complete playable game.

## Development

Requirements: JDK 21, Gradle 9.1, Docker/Podman.

```sh
cp .env.example .env
# Use a unique local database password and do not commit .env.
docker compose up -d db # compose.yaml is not yet included in the remote scaffold
# With PostgreSQL running, export DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD
export APP_ENV=development AUTH_MODE=dev HOST=127.0.0.1 PORT=8765
gradle --no-daemon test installDist
gradle --no-daemon run
```

Production requires `AUTH_MODE=oidc`, a valid HTTPS OIDC issuer and JWKS, a nonempty JWT audience, protected database credentials, and completed authorization checks.

## Deployment

- Render web service: `akashic-world-engine-dev` planned, not created.
- Neon external database `ai-trpg-akashic-dev` exists independently; **do not put Neon connection strings in source**.
- Cloudflare Pages `ai-trpg-client` exists but Godot content has not yet been deployed.
- `render.yaml` uses a development-only free plan and disables auto-deploy intentionally.
- Do not deploy or enable public game APIs until CI passes and real OIDC integration is verified.

## Missing / follow-up

- Import full frozen OpenAPI and JSON Schema contract documents from local handoff package.
- Reconcile the full original integration test suite and verify GitHub CI.
- Verify real Gradle build, dependency compatibility and schema migrations.
- Connect Godot client and real approved world lore.

## Locked bootstrap deployment

Set `AUTH_MODE=locked` and `APP_ENV=production` to boot without database credentials. Only `GET /health` succeeds; protected endpoints reject every request with HTTP 503 `AUTH_NOT_CONFIGURED`, even if the client supplies a bearer token. This is deliberately **not a playable deployment**.

To enable the actual game API, configure DB and OIDC secrets in the hosting dashboard, validate migration in a staging branch, then switch to `AUTH_MODE=oidc`. Do not set `AUTH_MODE=dev` on a hosted instance.
