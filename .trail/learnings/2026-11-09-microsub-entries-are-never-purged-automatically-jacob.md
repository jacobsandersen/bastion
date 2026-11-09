---
title: Microsub entries are never purged automatically; removed and read entries stay until the owner deletes them
author: Jacob
date: 2026-11-09
branch: fm/bastion-remove-retention
cost: { minutes: 1, tokens: 1561 }
id: l-0f2e9a39
scope: repo
status: active
replaces: l-57ef5b6f
src: agent
touches:
  - AGENTS.md
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/job/MicrosubSchedulers.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/entry/EntryRepository.kt
  - src/test/kotlin/dev/jacobandersen/bastion/microsub/internal/web/MicrosubIntegrationTest.kt
---

## Decided
The owner does not want old Microsub entries purged automatically, so the retention job and its config are gone. Removed and read entries stay forever, and a removed row keeps blocking a feed from re-adding that item. The old design deleted read or removed entries after a quiet period, assuming entries still in a feed are refreshed on every fetch. We keep refreshing last_seen_at on every upsert as the record of an entry still being in a feed, because a later cleanup scope may use it. Removing the job also deletes its persisted recurring job once at startup, because JobRunr keeps recurring jobs in its own tables and would otherwise fail every 6 hours.

## Tried and ruled out
The time-window purge itself: a quiet feed that answers conditional GETs with 304 never ingests, so last_seen_at goes stale, the purge deleted its read and owner-removed entries, and the next new post brought them back unread. Any future cleanup must not treat 304 silence as absence.

## Watch out
Deleting rows that a feed still lists is unsafe, since the next fetch re-inserts them as new unread entries. Nothing deletes entries automatically now, so keep it that way unless the owner asks. Any change to the upsert must keep refreshing last_seen_at.

In the code: MicrosubSchedulers deletes the old job id microsub-entry-retention (remove that call once every deployment has run it); MicrosubIntegrationTest covers the 304 scenario.
