---
title: "Bulk delete marks feed entries and hard-deletes only orphans, because nothing proves an entry is unlisted"
author: Jacob
date: 2026-11-10
branch: fm/bastion-bulk-delete
cost: { minutes: 5, tokens: 150316 }
id: l-4c5be743
scope: repo
status: active
src: agent
---

## Decided
Bulk delete (reader `POST /entries/delete`) reuses the single-delete rule. An entry that still has a feed becomes a marker, and the feed's next full fetch hard-deletes it if the feed no longer lists it. Only orphans (feed unfollowed, `feed:` source key) are hard-deleted at once.

## Tried and ruled out
Hard-deleting "unlisted" entries in the bulk call by comparing `last_seen_at` with the feed's `last_fetched_at`: a 304 fetch updates `last_fetched_at` but never `last_seen_at`, so listed entries would look unlisted and come back unread. A reliable signal needs a `last_parsed_at` set only on full fetches (not built).

## Watch out
Muted authors' entries are not skipped by bulk delete. Webmention entries (no feed) become markers, so a later webmention change cannot re-add them.
In the code: `EntryBulkDeleteService`, `BulkDeleteRepository`.
