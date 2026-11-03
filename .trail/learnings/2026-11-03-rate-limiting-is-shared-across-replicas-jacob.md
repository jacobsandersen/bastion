---
title: Rate limiting is shared across replicas in Redis and fails closed on Redis errors
author: Jacob
date: 2026-11-03
standing: true
id: l-eed73ebf
scope: repo
status: active
src: init-review
---

## Decided
- To scale to multiple replicas, the in-memory webmention limiter was replaced with a Redis-backed one. Allowing traffic when Redis is down would permit unlimited unauthenticated submissions.
- In the code: Webmention limiter uses Bucket4j on Redis with keys prefixed by bastion.webmention.rate-limit.key-prefix. Consume the pair bucket before the source bucket. Do not swallow Redis exceptions.

_From PR #95 here, Oct 28._
