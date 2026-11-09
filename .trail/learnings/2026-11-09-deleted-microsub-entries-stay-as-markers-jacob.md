---
title: "Deleted microsub entries stay as markers until their feed stops listing them, then are hard-deleted"
author: Jacob
date: 2026-11-09
branch: fm/bastion-tombstones
cost: { minutes: 6, tokens: 162146 }
id: l-7191193e
scope: repo
status: active
src: agent
---

## Decided
Deleting an entry only marks it. The row keeps its content so the owner can view it, and the upsert never rewrites it, so an item the feed still lists is not re-added as unread. A fetch that loaded the feed with at least one item hard-deletes the feed's markers it did not list. Failed, 304 and empty fetches never delete.
## Tried and ruled out
Compacting the payload at delete time (the design report's proposal): the owner wants deleted items viewable until the feed drops them. A grace period on last_seen_at: 304 fetches never refresh it, so it proves nothing about what the feed lists.
## Watch out
One fetch that omits an item is enough, so a stale CDN copy can drop a marker early. Markers of an unfollowed feed stay until it is followed again.
In the code: EntryDeletionService, EntryIngestService.ingestFetched, EntryRepository.touchMarked and deleteUnlistedMarkers, reader GET /channels/{uid}/deleted.
