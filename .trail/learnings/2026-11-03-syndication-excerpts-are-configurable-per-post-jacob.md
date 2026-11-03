---
title: "Syndication excerpts are configurable per post type: source order, truncation, and extra placeholders"
author: Jacob
date: 2026-11-03
branch: main
cost: { minutes: 19, tokens: 116285 }
id: l-c665f9bc
scope: repo
status: active
src: agent
touches:
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/types/SyndicationFormat.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/types/PostTypesConfig.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/types/PostTypeCatalog.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/syndication/SyndicationTextRenderer.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/syndication/SyndicationHttpClient.kt
  - src/main/kotlin/dev/jacobandersen/bastion/post/internal/syndication/SyndicationContentMapper.kt
---

## Decided
- The syndication excerpt is no longer always the post body. Each post type (or the defaults) can set which properties feed it, in priority order, plus the ellipsis, word vs grapheme truncation, and the minimum room an excerpt needs before the overflow format takes over. Unset fields inherit from defaults, then the old behavior, so existing config renders unchanged.
- Templates gained more placeholders (summary, published, tags, any property). If any of them is missing on a post, the fallback format is used, and the fallback may not use them.
- HTML-only content is stripped to plain text instead of being dropped.
- Updates replace every property the template shows downstream, except published, content, summary and url.

## Tried and ruled out
- Sources plus truncation only, without extra placeholders: the user chose the broader option.
- A full-body placeholder: only the excerpt is truncated, so a full body would always push posts to the overflow format. Use the source list instead.
- A YAML key named `break` for the truncation mode: it is a Kotlin keyword, so the key is `truncate`.

## Watch out
- Only the excerpt is truncated. A long summary or property value used as a placeholder pushes the post to the overflow format.
- The bundled default config is unchanged in behavior; the new settings are documented there as comments.

In the code: `ExcerptConfig.resolve` and `SyndicationTemplate.parse` in `post/internal/types/SyndicationFormat.kt`; `SyndicationTextRenderer.render`; `SyndicationHttpClient.serializeExcerptUpdate`; settings under `bastion.post-types.*.syndication.excerpt`.
