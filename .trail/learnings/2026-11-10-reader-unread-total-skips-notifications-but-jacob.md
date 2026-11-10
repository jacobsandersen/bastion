---
title: "Reader unread total skips notifications, but storage counts every channel and keeps deleted markers apart"
author: Jacob
date: 2026-11-10
branch: fm/bastion-reader-stats
cost: { minutes: 7, tokens: 147957 }
id: l-1061bb04
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/ModuleMetadata.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/channel/ChannelRepository.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/config/MicrosubConfig.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/entry/EntryRepository.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/moderation/ModerationSql.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/stats/StatsRepository.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/stats/ModuleMetadata.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/stats/StorageQueryService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/stats/UnreadQueryService.kt
---

## Decided
- The unread total leaves out the notifications channel (webmentions are not reading) but the channel list still shows it. Storage counts every channel, so its numbers are footprint, not reading.
- Entries marked deleted are never unread and are reported as `removed`, separate from `entries`; their row bytes still count as storage.
- Storage is cached per replica with a configurable TTL (default 5 minutes). Warning thresholds are off by default and only add a `warnings` list.

## Tried and ruled out
- Summing the jsonb column for size: it under-reports about 4x; use whole-row `pg_column_size(e.*)`.

## Watch out
- Per-channel and per-feed bytes do not add up to on-disk bytes (indexes and free space are unattributed).
- Entries without a followed feed count toward their channel but appear under no feed.

In the code:
- microsub/stats/, microsub/internal/stats/StatsRepository.kt, ModerationSql.NOT_MODERATED shared predicate, bastion.microsub.storage-* config.
