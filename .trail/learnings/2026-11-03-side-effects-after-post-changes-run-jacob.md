---
title: "Side effects after post changes run as durable async events, with failures kept visible"
author: Jacob
date: 2026-11-03
standing: true
id: l-0f274655
scope: repo
status: active
src: init-review
---

## Decided
- Listeners now use Modulith's event publication registry so failed work is retried by a cluster-safe job. Swallowing exceptions would hide failures from the registry.
- In the code: Use @ApplicationModuleListener and let exceptions propagate. Keep republish-outstanding-events-on-restart=false. The event_publication table is created by Modulith, so do not add a Flyway migration for it.

_From PR #94 here, Oct 28._
