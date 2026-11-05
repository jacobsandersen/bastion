---
title: "A reply's self-declared permalink is trusted for identity only on the same site; cross-site permalinks only help threading"
author: Jacob
date: 2026-11-05
branch: feat/salmention
cost: { minutes: 28, tokens: 30873 }
id: l-f98c0dcf
scope: repo
status: active
src: agent
touches:
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/internal/data/service/ReceivedWebmentionService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/internal/service/ReceivedWebmentionAnalyzer.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/internal/service/WebmentionReceiverService.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/internal/service/WebmentionThreadResolver.kt
  - src/test/kotlin/dev/jacobandersen/bastion/webmention/internal/data/service/ReceivedWebmentionServiceTest.kt
  - src/test/kotlin/dev/jacobandersen/bastion/webmention/internal/service/ReceivedWebmentionAnalyzerTest.kt
  - src/test/kotlin/dev/jacobandersen/bastion/webmention/internal/service/WebmentionThreadResolverTest.kt
  - AGENTS.md
  - src/main/kotlin/dev/jacobandersen/bastion/api/internal/post/dto/WebmentionDto.kt
  - src/main/kotlin/dev/jacobandersen/bastion/webmention/internal/data/domain/ReceivedWebmention.kt
---

## Decided
- A received reply's u-url (its declared permalink) counts as its identity, for deduplicating against nested copies or taking them over, only when it is on the same origin as the webmention source. Otherwise only the source URL identifies it.
- Cross-site u-urls (e.g. Bridgy: brid.gy source, mastodon.social permalink) are still used for threading, as lowest-priority fallback keys that never displace a real response. The user required this: Mastodon reply chains backfed through Bridgy must keep threading, and losing that "breaks basic salmention".
- The post's own content URLs are never used as thread keys, so a spoofed u-url equal to the post URL cannot re-parent every reply.
- The API exposes urlSameOrigin so the frontend links to url only when it is same-origin, and to sourceUrl otherwise.

## Tried and ruled out
- Trusting any u-url for identity: anyone with one verified webmention could hide or impersonate another reply.
- Same-origin rule with no threading fallback: Bridgy reply chains stopped threading.
- Allowlisting proxy hosts like brid.gy for identity: rejected as extra config; the user chose plain same-origin.
- Dropping cross-origin urls from the API: Bridgy rows would lose their permalink.

## Watch out
- Accepted tradeoff: a Bridgy reply that is also displayed nested on another site can appear twice.
- Bad-actor content is meant to be handled by future moderation, not by tightening these rules.

In the code: WebmentionThreadResolver (fallback keys, own-URL exclusion via ContentUrlMatcher::isOwnContentUrl), ReceivedWebmentionService key matching, WebmentionView.urlSameOrigin; PR #112.
