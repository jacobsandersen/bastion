---
title: Never reformat already-applied database migration files; it stops the app booting
author: Jacob
date: 2026-11-03
standing: true
id: l-65df1b87
scope: repo
status: active
src: init-review
---

## Watch out
- A cleanup commit reformatted old migrations and the checksum validation at startup failed, preventing boot. The files had to be restored verbatim.
- In the code: Do not run formatters or edit whitespace in existing Flyway migrations (V1..V15+ under db/migration). Add a new migration for any change, and exclude migrations from formatting tools.

_From PR #24 here, Sep 20._
