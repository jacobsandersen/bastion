---
title: Microsub timelines are ordered by arrival so a before poll cannot miss a late entry
author: Jacob
date: 2026-11-07
branch: fix/microsub-arrival-order
id: l-6099f37c
scope: repo
status: active
src: agent
---

## Decided
Order timelines and cursors by (created_at, published, id): when Bastion first stored the entry, then publish date within one fetch. A new feed's backfill keeps its date order, and an entry that arrives late with an old published date always sorts after cursors handed out earlier. The spec sets no timeline order and treats cursors as opaque. created_at is set on insert only, so existing rows needed no data migration.

## Tried and ruled out
Ordering by published date (the old behaviour): an aggregated feed such as Google News produced entries published before a client's newest one, which sorted behind its before cursor, so the unread count included entries polling could never return (badge 2, one shown). Push delivery would not help, since the missed entry would still sort behind the cursor. Changing mark_read through an entry to arrival order: Grimoire's mark all read anchors on the entry newest by published date, so it would leave later arrivals unread.

## Watch out
Cursors issued before the change are two-part (published and id); they are resolved by looking the entry up, and a purged entry means the request restarts from the newest page. Two fetches running at once can in theory commit out of timestamp order; clients should still reconcile against the unread count.

In the code: Cursor.kt, EntryRepository.page and cursorOf, TimelineService.cursor, migration V27.
