# World Engine authentication end-to-end verification

Status: synthetic-JWT CI verified; real Neon-issued JWT NOT YET VERIFIED.

## Verified in CI

GitHub Actions workflow run `40800505964` succeeded at commit `acdb0ea65d27c3481f279c08c6a3697f34ad2c62`.

`AuthenticatedWorldFlowTest` runs the Ktor HTTP routes against PostgreSQL using an ephemeral Ed25519 key and signed fixture JWT. It checks:
- unauthorized and wrong-audience requests are rejected;
- a player can create a fixture-v1 world, talk, move, rest and read visible events;
- a different player cannot read another world;
- replay state remains consistent with recorded events;
- private NPC beliefs are absent from viewer-visible responses.

**This does not prove real Neon Auth interoperability.** A cryptographically valid synthetic token may have different claims from Neon Auth's actual token endpoint.

## Live Neon Auth validation gates

1. Use the branch-specific Managed Better Auth client `@neondatabase/auth`. A browser-based test user signs in through `signIn.email()` or configured Google OAuth, and requests a short-lived JWT via `authClient.token()` (`data.token`). The client's JWT is for the World Engine only; ChatGPT inference OAuth remains client-local and separate.
2. Inspect ONLY the non-secret claim keys and the values/types of `iss`, `aud`, `alg`, `exp`, `iat` and `kid`, without printing the raw JWT, bearer header, cookie, password or private key. If claims differ, review the expected Neon token contract, then implement and test required checks without relaxing signature or expiry.
3. Confirm the public Neon JWKS serves matching Ed25519 key IDs and that Ktor accepts the actual token. Include negative tests (tampered JWT, expired JWT, wrong audience/issuer, owner mismatch).
4. Set verified `JWT_AUDIENCE` in Render's secret environment. `JWT_ISSUER` and `JWT_JWKS_URL` must identify the same branch as the JWT.
5. Before switching `AUTH_MODE` from `locked` to `oidc`, use a staging instance or an isolated Render service to run the full live test. Never set `AUTH_MODE=dev` on Render.
6. Enable the actual service only when sign-in and full world lifecycle succeed and anti-abuse settings, trusted origins, rate limiting and CORS are verified.

## DB safety

The Neon default `akashic` database already has Flyway schema version 1 and six game tables. Running migrations on every production startup using the pooled JDBC connection was removed in commit `251741f6`. In regular OIDC mode the server now checks schema version 1 and fails closed if it is missing. Explicit direct-connection Flyway runs remain possible only with `DB_BOOTSTRAP_MIGRATE=true` in locked mode; it is disabled in Render.

## Current deployed protections

- `AUTH_MODE=locked` intentionally denies game API access.
- `DB_BOOTSTRAP_MIGRATE=false` prevents repeated bootstrap.
- JDBC credentials are stored in Render, not GitHub.
- `DB_PREFLIGHT` is read-only and its output does not include passwords.

## Client integration

Godot client requires a session/token acquisition path, a distinct game identity and world API HTTP adapter. Do not send ChatGPT access tokens to the World Engine.

Server routes: `POST /v1/worlds`, `GET /v1/worlds/{worldId}/view`, `POST /v1/worlds/{worldId}/commands`, `GET /v1/worlds/{worldId}/events`.
