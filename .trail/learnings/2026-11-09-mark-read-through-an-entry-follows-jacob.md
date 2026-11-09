---
title: "mark_read through an entry follows the timeline's arrival order, same as paging"
author: Jacob
date: 2026-11-09
branch: fm/bastion-microsub-cleanup
cost: { minutes: 1, tokens: 2575 }
id: l-d3bb4628
scope: repo
status: active
replaces: l-6099f37c
src: agent
---

## Decided
Order timelines, cursors and `mark_read` with `last_read_entry` all by (created_at, published, id): when Bastion first stored the entry, then publish date within one fetch. A new feed's backfill keeps its date order, and an entry that arrives late with an old published date always sorts after cursors handed out earlier. Marking read through an anchor marks only what is at or below it in that order, as the Microsub spec's "the entry and everything before it in the timeline" means. created_at is set on insert only, so no data migration was needed.

## Tried and ruled out
Ordering by published date (the old behaviour): an aggregated feed such as Google News produced entries published before a client's newest one, which sorted behind its before cursor, so the unread count included entries polling could never return. Keeping `mark_read` through an entry on publish order to suit Grimoire's mark-all-read anchor: it swallowed later arrivals the client never saw (a late p2 published before the anchor p5 was marked read), the one real spec departure, so Grimoire now anchors on the top of the timeline it synced.

## Watch out
Cursors issued before the change are two-part (published and id); they are resolved by looking the entry up. Two fetches running at once can in theory commit out of timestamp order; clients should still reconcile against the unread count. An old client anchoring on its publish-newest entry may leave some uncached later arrivals unread.

In the code: Cursor.kt, EntryRepository.page, cursorOf and markReadThrough, TimelineService.cursor, migration V27.
