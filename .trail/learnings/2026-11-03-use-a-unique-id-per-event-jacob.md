---
title: "Use a unique id per event, not per post, for message deduplication"
author: Jacob
date: 2026-11-03
standing: true
id: l-57389c68
scope: repo
status: active
src: init-review
---

## Watch out
- Keying deduplication by post id collapsed distinct updates for the same post and silently dropped them. The 5s polling job also only ran in bursts.
- In the code: Set Nats-Msg-Id from the outbox row id. This content outbox code was removed with the monolith revert, so apply the lesson to any future dedup keys.

_From PR #85 here, Oct 6._
