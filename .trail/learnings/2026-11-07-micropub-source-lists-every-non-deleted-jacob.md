---
title: Micropub source lists every non-deleted post and always carries the canonical url
author: Jacob
date: 2026-11-07
branch: fix/micropub-source-url-and-visibility
cost: { minutes: 6, tokens: 58427 }
id: l-551ccd11
scope: repo
status: active
src: agent
touches:
  - src/main/kotlin/dev/jacobandersen/bastion/micropub/internal/query/SourceService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/repository/PostRepository.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/store/PostService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/query/PostQueryService.kt
  - src/test/kotlin/dev/jacobandersen/bastion/micropub/internal/controller/MicropubControllerIntegrationTest.kt
---

## Decided
Authenticated Micropub reads are not subject to feed visibility rules: the source list returns drafts, private and unlisted posts too. Only the public API feed filters by status and visibility. Deleted posts stay out of the list (a lookup by URL answers 410). Every source item carries its canonical URL as an ordinary mf2 `url` property so clients can edit listed posts directly.

## Tried and ruled out
A top-level `url` field beside `type`/`properties`: rejected to keep the response plain mf2, like the downstream syndication payload.

## Watch out
The list keeps `url` even when the client filters `properties`, because the list is where clients need it. A single lookup with a `properties` filter returns only the requested properties.

In the code: SourceService (micropub adapter) adds the url; PostService.findAllPosts backs PostQueryService.list; findPublicPosts remains for the public feed.
