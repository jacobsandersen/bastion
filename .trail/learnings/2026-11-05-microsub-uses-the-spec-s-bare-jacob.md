---
title: "Microsub uses the spec's bare scope names instead of module-prefixed ones"
author: Jacob
date: 2026-11-05
branch: feat/microsub
cost: { minutes: 28, tokens: 367652 }
id: l-71d05ebe
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
---

## Decided
Microsub registers `read`, `follow`, `mute`, `block` and `channels` as bare scope names, an exception to the rule that new modules prefix their scopes (e.g. `notes:read`). Reader clients (Monocle, Indigenous, Together) request exactly the spec names, so prefixed scopes would leave them with tokens that never work.

## Tried and ruled out
Prefixed `microsub:*` scopes: consistent with the rule, but standard clients would need manual configuration.

## Watch out
Microsub now effectively owns the generic `read` scope. Another module that wants a read scope must use a prefixed name.

In the code: microsub/internal/security/MicrosubScope.kt (MicrosubScopeContributor); AGENTS.md IndieAuth section notes the exception.
