# ADR-0001: Neon Auth and World Engine rollout (2026-10-29)

Status: **development integration — game API remains locked**

## Confirmed infrastructure

- Render service: `akashic-world-engine-dev` / Singapore / free, health-only, deployed from GitHub at commit `ed591f4`.
- Neon PostgreSQL project: `ai-trpg-akashic-dev` (`purple-dust-90057652`), region Singapore, database `akashic`, PostgreSQL 17.
- Neon Auth Better Auth integration provisioned on the **main** DB branch. Managed auth tables are in the separate `neon_auth` schema.
- Authentication endpoint: `https://ep-orange-bonus-a1zqitqt.neonauth.ap-southeast-1.aws.neon.tech/akashic/auth`
- JWKS endpoint: `https://ep-orange-bonus-a1zqitqt.neonauth.ap-southeast-1.aws.neon.tech/akashic/auth/.well-known/jwks.json`
- Schema validation branch `world-engine-schema-validation` (`br-damp-tooth-a1rrk0v8`): applied the contents of `V1__turn_core.sql` in one SQL transaction and verified 6 `public` tables, enabled append-only trigger, and 0 game worlds.

The production/default Neon **public** schema has **not** had the World Engine Flyway migration applied. Do not mark the test branch as deployed production or use it as an authority for any live worlds.

## Authentication split

- Neon Auth authenticates players into game accounts. It must remain separate from ChatGPT user-side inference authorization.
- Client ChatGPT OAuth tokens and image generation permissions NEVER transit to the World Engine.
- World Engine verifies only the intended game ID token; it MUST validate signature and key, algorithm, issuer, audience, exp, iat and subject.
- Ktor verifier was updated on 2026-10-29 to require **EdDSA/Ed25519** signatures for Neon Auth. A locally generated Ed25519 test JWT passed verification and forged/expired/wrong-audience fixtures were rejected in GitHub CI. Actual Neon-issued token compatibility is **not yet verified**: before activation inspect an actual issued ID/JWT token (do not expose it), published JWKS metadata, and provider documentation. If Neon Auth issues another algorithm/claims set or cookie sessions, adapt and test an appropriate supported verification method. Never disable signature, issuer or audience checks just to make authentication pass.
- At provision time Neon Auth's development settings allowed email/password signup without required email verification. **Do not link a public registration UI or advertise it until sign-up policies, domain allowlist, verification and abuse controls have been reviewed.**
- Unknown token -> 401. Wrong viewer/world -> 404. Wrong actor ID -> 403. Missing server auth configuration -> 503.
- Client receives only actor-visible state, never hidden beliefs or private canonical lore.

## Safe activation gates

1. Verify current Render deployment and health. Its `AUTH_MODE` remains `locked`.
2. Run Gradle, PostgreSQL and fail-closed auth tests against the intended commit.
3. Confirm the exact Neon Auth token exchange, issuer, audience, algorithm and signing key rotation behavior. Add a real token integration test.
4. Review the hosted registration policy. Set trusted origins and redirect URLs explicitly. Keep test users separate.
5. Obtain the existing Neon connection string only through the Neon console/provider; never place it into GitHub, logs or chat. Configure Render environment secrets using secure dashboard controls. Set JDBC URL, username and password separately, TLS required.
6. Rehearse Flyway against a fresh DB branch (the earlier validation branch was applied via SQL rather than Flyway, so do not run Flyway blindly against it).
7. Apply the checked migration to the intended primary database via the application's standard Flyway path, after ensuring the target database is correct.
8. With Render configured, enable `AUTH_MODE=oidc` and perform authenticated world creation, idempotent turns, replay, rejection of forged tokens and cross-world isolation tests.
9. Retain `autoDeployTrigger: off` until CI and end-to-end verification prove deployment safety.

## Practical configuration reference (never add secrets)

```text
APP_ENV=production
AUTH_MODE=locked     # switch to oidc only after verifying token compatibility
HOST=0.0.0.0
PORT=10000
DATABASE_URL=jdbc:postgresql://<neon-host>/akashic?sslmode=require
DATABASE_USER=<role>
DATABASE_PASSWORD=<secret in Render>
JWT_ISSUER=<verified issuer claim>
JWT_AUDIENCE=<verified audience claim>
JWT_JWKS_URL=<provider JWKS endpoint>
```

## Immediate next task

Build a Neon Auth integration fixture that captures only non-secret token metadata (`alg`, `iss`, `aud` shape) and verifies one short-lived token in-memory without saving it to CI artifacts, logs, repository or user data files. Then update Ktor verifier only if the actual claims require a change. Add negative tests.

The goal is a fully verifiable, publicly reachable **API that denies unauthorized access**, not public access to game world state by default.

## Verification update — 2026-10-29

- `Auth.kt` now validates EdDSA/Ed25519 signatures using a cached, pinned-host Neon Auth JWKS, and enforces issuer, audience, subject and token times.
- `NeonJwtVerificationTest.kt` creates and signs disposable Ed25519 JWTs in CI; valid and invalid signatures and claims are tested without exposing credentials.
- GitHub Actions run `40797655493` passed at commit `4dcf6eee97398d58bec7fa5b24c6d04fe9dc96a9`.
- **Not yet confirmed:** Real Neon Auth token claims (`aud` and `iss` in particular), live JWKS algorithm and key rotation, browser login and the protected API path. Do not replace the locked Render deployment with an OIDC-enabled one yet.
- Database schema remains only in the isolated validation branch. The Neon primary DB only has managed `neon_auth` schema, not World Engine tables.
