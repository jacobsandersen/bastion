---
title: "Saved folders are frozen copies with no link to the microsub tables, so no entry lifecycle can delete them"
author: Jacob
date: 2026-11-10
branch: fm/bastion-saved-folders
cost: { minutes: 90, tokens: 3275 }
id: l-06cf8a82
scope: repo
status: active
src: agent
---

## Decided
The reader's saved folders keep a full copy of an entry's jf2 in their own tables (`reader_saved_folders`, `reader_saved_items`) with no foreign key to the microsub tables. Removing an entry, a feed dropping it, bulk delete, a block, a retracted webmention, unfollow and channel delete therefore cannot reach a saved copy; only an explicit item or folder delete does, and nothing purges them. One folder per item, the copy is frozen, saving also works for a deleted marker (rescue), blocks do not delete saved items, and no new scope is used (`read`, plus `channels` for folder create/rename/delete). Saved data is its own `saved` block in `GET /storage`; `microsub.on_disk_bytes` stays microsub-only.

## Tried and ruled out
A new `reader:saved` scope (forces a re-consent for no safety gain on a single-user server), many-to-many folder membership (makes move, folder delete and per-folder size harder for a case nobody asked for), and a server-side atomic "save and remove" (would need a new microsub command surface).

## Watch out
Saved items are the first data in the reader family that cannot be re-fetched, so a lost database loses them. Keep the saved queries off `ModerationSql.NOT_MODERATED`, otherwise the keep-regardless guarantee turns conditional. The reader may not read microsub internals: the copy comes through `microsub::microsub-entries` (`EntrySnapshotQueryService`).
In the code: `reader/internal/saved/SavedService`, `V30__create_reader_saved_tables.sql`, `SavedIntegrationTest`.
