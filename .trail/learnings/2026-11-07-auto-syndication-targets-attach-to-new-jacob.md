---
title: "Auto syndication targets attach to new posts only, and clients cannot remove them on edit"
author: Jacob
date: 2026-11-07
branch: main
cost: { minutes: 13, tokens: 44905 }
id: l-cddc38d1
scope: repo
status: active
src: agent
touches:
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/syndication/SyndicationConfig.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/syndication/SyndicationTargets.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/commands/PostCommandService.kt
  - src/test/kotlin/dev/jacobandersen/bastion/post/internal/syndication/AutoSyndicationTargetTest.kt
  - AGENTS.md
  - src/test/kotlin/dev/jacobandersen/bastion/post/internal/syndication/SyndicationConfigTest.kt
  - src/test/kotlin/dev/jacobandersen/bastion/post/internal/syndication/SyndicationReconciliationTest.kt
---

## Decided
A syndication target can be marked auto. It is attached to every new post whatever the client sends, and hidden from the targets advertised to Micropub clients, so compliant clients don't show a toggle for it. Edits cannot drop an auto target the post already has. Posts without that target are never given it later, so there is no backfill and editing an old post does not start syndicating it.

## Tried and ruled out
- Letting an explicit removal win on edit: clients that send a replace list containing only the targets they know about would silently retract auto copies.
- Backfilling or attaching on next edit: would syndicate the old archive when the flag is turned on.

## Watch out
A client cannot deliberately retract an auto copy from an existing post; only deleting the post does that.

In the code:
- `SyndicationConfig.Target.auto`, `autoTargetUids()`
- `SyndicationTargetQueryService.targets()` filters auto targets
- `PostCommandService.create` unions auto uids; `applySyndicationChange` re-adds auto uids already in the post's desired rows
- Test: `AutoSyndicationTargetTest`
