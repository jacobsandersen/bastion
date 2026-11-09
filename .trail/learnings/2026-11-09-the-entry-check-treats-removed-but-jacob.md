---
title: The entry check treats removed-but-kept entries and malformed ids as gone
author: Jacob
date: 2026-11-09
branch: fm/bastion-entry-check
cost: { minutes: 1, tokens: 1662 }
id: l-e3d7a035
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/ModuleMetadata.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/entries/EntryStatusQueryService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/entries/ModuleMetadata.kt
  - src/main/kotlin/dev/jacobandersen/bastion/microsub/internal/entry/EntryRepository.kt
  - src/main/kotlin/dev/jacobandersen/bastion/reader/ModuleMetadata.kt
  - src/main/kotlin/dev/jacobandersen/bastion/reader/internal/ReaderApi.kt
  - src/main/kotlin/dev/jacobandersen/bastion/reader/internal/web/ReaderCapabilities.kt
  - src/main/kotlin/dev/jacobandersen/bastion/reader/internal/web/ReaderController.kt
  - src/main/kotlin/dev/jacobandersen/bastion/reader/internal/web/ReaderError.kt
---

## Decided
The reader entry check answers "gone" for anything a client should drop from its cache: entries marked removed (the row is kept so feeds do not re-add the item), hard-deleted rows, ids from another channel, and malformed ids. Malformed ids are reported as gone rather than rejected with 400, because a cached id that cannot match anything is a ghost to drop. Only an empty list, more than 500 ids, or an unknown channel are errors.

## Tried and ruled out
Returning 400 for non-UUID ids as the Microsub `remove` action does: that would make one bad cached id fail the whole batch.

## Watch out
Marked rows keep their payload until the feed stops listing them, so "row exists" is not "entry is live"; the check must filter on `removed_at is null`.

In the code: `EntryRepository.liveIds`, `EntryStatusQueryService.gone`, `ReaderController.checkEntries`.
