---
title: "Scopes are contributed by each module, with no central scope list"
author: Jacob
date: 2026-11-03
standing: true
id: l-0dcdf4dd
scope: repo
status: active
src: init-review
---

## Decided
- The IndieAuth provider used to hardcode Micropub's scopes. Now each module registers its own scopes so the provider stays generic.
- In the code: To add scopes: depend on indieauth::indieauth-scopes, implement IndieAuthScopeContributor, enforce scopes on your own resources, and use prefixed names like notes:read. Do not re-add IndieAuthConfig.allowedScopes or DEFAULT_SCOPES.

_From PR #98 here, Oct 29._
