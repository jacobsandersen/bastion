---
title: "Bastion's reader-client extras live only in the reader module, advertised through an IndieAuth metadata contributor"
author: Jacob
date: 2026-11-09
branch: fm/bastion-microsub-cleanup
cost: { minutes: 1, tokens: 2575 }
id: l-4d737d1f
scope: repo
status: active
replaces: l-5a7e27cd
src: agent
---

## Decided
Grimoire-only extras (on-demand refresh, live entries stream, feed details, entry check) are served only by the `reader` module at `/reader/v1`, so Microsub stays spec-only. The legacy `/microsub/refresh`, `/microsub/events` and the `format`/`websub` fields on Microsub feed objects were removed once the reader-aware client was deployed. The reader module reaches Microsub only through named interfaces. The IndieAuth metadata gets `bastion_reader_api` through an `IndieAuthMetadataContributor` that the reader module implements, so IndieAuth never hardcodes another module's path. The reader API checks Microsub's `read` scope and defines none, so existing sessions need no re-consent.
The SSE hub (`EntryEvents`) stays in Microsub, because Microsub publishes to it and cannot depend on reader.

## Tried and ruled out
Publishing an event from Microsub and keeping the hub in reader: it would need a Microsub-to-reader dependency cycle.

## Watch out
Old clients that only know the legacy routes degrade to plain Microsub: no refresh nudge, live stream or feed chips.

In the code: reader/internal/web/ReaderController.kt, microsub/feeds/, microsub/events/EntryEvents.kt, indieauth/metadata/IndieAuthMetadataContributor.kt.
