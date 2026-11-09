---
title: "Bastion's reader-client extras live in a separate reader module that the IndieAuth metadata advertises through a contributor"
author: Jacob
date: 2026-11-09
branch: fm/bastion-reader-module
cost: { minutes: 6, tokens: 175078 }
id: l-5a7e27cd
scope: repo
status: active
src: agent
---

## Decided
Grimoire-only extras (on-demand refresh, live entries stream, feed details) are served by a `reader` module at `/reader/v1`, so Microsub stays spec-only. The reader module reaches Microsub only through two named interfaces. The IndieAuth metadata gets `bastion_reader_api` through an `IndieAuthMetadataContributor` that the reader module implements, the same pattern as scope contributors, so IndieAuth never hardcodes another module's path. The reader API checks Microsub's `read` scope and defines none, so existing sessions need no re-consent and an older Bastion never sees an unknown scope.
The SSE hub (`EntryEvents`) stays in Microsub and is shared by the legacy `/microsub/events` route and `/reader/v1/events`, because Microsub cannot depend on reader.

## Tried and ruled out
Publishing an event from Microsub and keeping the hub in reader: the legacy route that must keep working during migration could not reach the hub without a Microsub-to-reader dependency cycle.

## Watch out
The legacy `/microsub/refresh`, `/microsub/events` and the `format`/`websub` feed fields are meant to be removed in a later step. Until then they share the code behind the reader API (`FeedDetailsMapper`, `FeedRefreshService.refreshStale`), so change those, not copies.

In the code: reader/internal/web/ReaderController.kt, microsub/feeds/, microsub/events/EntryEvents.kt, indieauth/metadata/IndieAuthMetadataContributor.kt.
