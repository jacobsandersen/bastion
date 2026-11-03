---
title: "Concurrent edit conflicts return HTTP 400 to Micropub clients, not 409"
author: Jacob
date: 2026-11-03
standing: true
id: l-85f736fb
scope: repo
status: active
src: init-review
---

## Decided
- Optimistic locking was added to posts, but Micropub clients do not understand 409. The conflict is mapped to invalid_request with a retry message.
- In the code: Keep the version column internal (no ETag/If-Match). In Micropub, map PostCommandConflictException to 400 invalid_request. Post updates must refresh DB-generated columns after write so stale versions surface as optimistic lock failures.

_From PR #96 here, Oct 28._
