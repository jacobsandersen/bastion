---
title: "Splitting into separate services became a distributed monolith, so we restored the single app"
author: Jacob
date: 2026-11-03
standing: true
id: l-5824cf0d
scope: repo
status: active
src: init-review
---

## Tried and ruled out
- The Forge/Sigil/Beacon/Conduit decomposition produced services too coupled to run independently. The team reverted to the 1.3.33 monolith and kept only the microformats2 parser as an external library.
- In the code: Do not reintroduce separate services, content-client, outbox/projections or the internal API. Keep one Gradle module under src/ and use Spring Modulith boundaries for separation. Versioning resumed from 1.3.33.

_From PR #87 here, Oct 27._
