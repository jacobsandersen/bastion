---
title: WebSub content pushes only trigger a feed re-fetch; the pushed body is never ingested
author: Jacob
date: 2026-11-05
branch: feat/microsub
cost: { minutes: 29, tokens: 367652 }
id: l-c0fe60f8
scope: repo
status: active
src: agent
---

## Decided
The WebSub subscriber lives in the websub module and publishes a thin WebsubContentNotifiedEvent (unique eventId, subscription id, topic). Microsub reacts by re-fetching the feed instead of parsing the pushed body. This keeps the event_publication table small, follows the thin-event pattern used by PostChangedEvent, and makes duplicate or out-of-order pings converge on the feed's current state.

## Watch out
A refresh requested while a fetch of the same feed is running must not be lost. The request sets refresh_requested_at, and releasing the claim makes the feed due again immediately if a request arrived after the fetch started.
Subscriptions are shared per (topic, hub). Only unsubscribe once no feed row references the subscription.

In the code: websub/internal/subscriber/WebsubSubscriber.kt, microsub/internal/feed/FeedRefreshService.kt, FeedRepository.release().
