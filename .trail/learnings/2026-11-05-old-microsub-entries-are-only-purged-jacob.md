---
title: "Old Microsub entries are only purged after they drop out of their feed, so they never come back unread"
author: Jacob
date: 2026-11-05
branch: feat/microsub
cost: { minutes: 28, tokens: 56155 }
id: l-57ef5b6f
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/entry/EntryIngestService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/feed/FeedRefreshService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/feed/FollowService.kt
  - src/test/kotlin/dev/jacobandersen/bastion/microsub/internal/web/MicrosubIntegrationTest.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/entry/EntryRepository.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/listener/MicrosubListeners.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/events/ReceivedWebmentionChangedEvent.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/read/WebmentionReadModels.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/read/WebmentionReadService.kt
---

## Decided
Retention deletes a read or removed entry only once no fetch has seen it for the whole retention period. Every upsert refreshes last_seen_at, even when the data is unchanged. Posts still listed in a feed are never purged, so the next poll can't re-insert them as unread.

## Tried and ruled out
Purging by updated_at: stable feeds re-inserted purged posts as unread on every retention cycle.
Tombstones of purged keys: work, but the tombstone table grows and needs its own cleanup.
Skipping items older than the retention window at ingest: undated items still come back, and old posts added to a feed late are hidden.

## Watch out
Any change to the upsert must keep refreshing last_seen_at when nothing else changed.

In the code: microsub_entries.last_seen_at (migration V26), EntryRepository.upsert/purge.
