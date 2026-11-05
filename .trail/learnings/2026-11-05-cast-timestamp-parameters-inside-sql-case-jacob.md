---
title: "Cast timestamp parameters inside SQL CASE expressions, or Postgres treats them as text"
author: Jacob
date: 2026-11-05
cost: { minutes: 12, tokens: 222958 }
id: l-cf335ed2
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/listener/MicrosubListeners.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/events/ReceivedWebmentionChangedEvent.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/read/WebmentionReadModels.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/read/WebmentionReadService.kt
  - src/test/kotlin/dev/jacobandersen/bastion/microsub/internal/web/MicrosubIntegrationTest.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/channel/ChannelService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/config/MicrosubConfig.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/entry/EntryIngestService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/entry/EntryRepository.kt
---

## Decided
Timestamp bind parameters used inside a SQL `CASE` (or any expression where Postgres cannot infer the type from a column) are wrapped in `cast(:param as timestamptz)`.

## Watch out
pgjdbc sends `java.sql.Timestamp` parameters with an unspecified type so the server can choose timestamp vs timestamptz. Assigned directly to a column that is fine, but inside `CASE ... THEN :a ELSE :b END` both branches are unknown and Postgres resolves them to text, failing with "column is of type timestamp with time zone but expression is of type text". The Microsub feed claim release hit this on every finished fetch and no test ran that path until integration tests called the refresh directly.

In the code: `FeedRepository.release()` in microsub/internal/feed uses `cast(:now as timestamptz)` / `cast(:next as timestamptz)`.
