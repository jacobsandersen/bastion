---
title: "Salmention resends go upstream only, and only when the displayed responses actually change"
author: Jacob
date: 2026-11-05
branch: main
cost: { minutes: 21, tokens: 192038 }
id: l-5c9525d5
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
---

## Decided
- When the responses shown on a post change, Bastion re-sends webmentions only to the targets that post already delivered to (upstream, per the Salmention spec). The user chose this over also pinging the parent responder downstream (Alice→Bob), even though their own example described the downstream ping.
- Replies to replies are threaded when the API reads them, not stored as a parent column, so replies that arrive out of order still thread.
- Nested replies found inside a source are stored as their own rows tied to that source. A directly received webmention always wins over a nested copy.

## Tried and ruled out
- Nesting webmentions as a tree in the API response: kept the flat list plus parent pointer so existing clients don't break.

## Watch out
- The resend is gated by a fingerprint of what the post displays, which survives the reset to pending before each re-verification. Comparing state alone cannot work, because state is always PENDING by then. Removing the gate brings back ping-pong loops between sites.
- Verification errors are transient: they keep the fingerprint and nested rows and trigger no resend.

In the code: webmention/internal/service/SalmentionService.kt, WebmentionThreadResolver.kt, ReceivedWebmentionService.markVerified/replaceNested, V23__add_salmention_threading.sql
