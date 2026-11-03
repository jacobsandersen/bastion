---
title: Production fails to start without the externally mounted post types config file
author: Jacob
date: 2026-11-03
standing: true
id: l-94e3ce0f
scope: repo
status: active
src: init-review
---

## Watch out
- Post types moved from code into an external YAML file that is imported at startup. Startup failing without it is intentional.
- In the code: Provide the post-types YAML at /etc/config/post-types.yaml (configmap) or set BASTION_POST_TYPES_CONFIG_FILE. It is loaded via spring.config.import.

_From PR #70 here, Oct 5._
