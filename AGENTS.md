# Akashic World Engine — contributor rules

1. Contract version v1.0.0 defines observable behavior. Changes require an ADR and client review.
2. World Engine alone determines world truth, time, valid commands and durable events.
3. Client ChatGPT inference and image generation cannot directly mutate world state.
4. Preserve atomicity across world_events, worlds, scheduled_events, visible_events and command_results.
5. Per-world state changes must hold a row lock before validating versions and mutating.
6. Never disclose NPC private beliefs through viewer endpoints, narration context or logs.
7. Replay confirmed events without calling LLM or rerolling random results.
8. Never enable development bearer authentication on public services.
9. Never commit passwords, OAuth tokens or actual private lore.
10. Keep Godot code in a separate repository, communicate through versioned APIs.
11. Required validation: Gradle compilation, PostgreSQL integration tests, schema validation once contract files are uploaded.
12. Do not claim deployment, build success or OIDC validation without observed results.
