# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and
sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.

## Build and test

- `./gradlew test` runs the full suite; the Spring Boot tests use Testcontainers (needs Docker).
- `./gradlew ktlintCheck` runs the linter (ktlint 1.8.0).
- Modulith module and named-interface metadata lives in Kotlin `ModuleMetadata.kt` files annotated with
  `@PackageInfo` (the Kotlin-friendly alternative to `package-info.java`). Keep one per package that needs
  `@ApplicationModule` or `@NamedInterface`.
- Jackson 3 (`tools.jackson.*`) is used, not Jackson 2; `@JsonProperty` still comes from
  `com.fasterxml.jackson.annotation`.
- JobRunr: methods invoked from a scheduled/enqueued job lambda must not use Kotlin default parameter values - JobRunr
  fails to schedule them (`UnsupportedOperationException` at `JobDetailsBuilder`). Lambda args are also serialized once
  at registration and replayed on every fire, so never pass a computed time (e.g. `Instant.now()`) through the lambda -
  compute it inside the invoked method, as `IndieAuthRowPurgeService.purge()` does.

## Module boundaries and CQRS

- Module roots contain only `ModuleMetadata.kt` plus public named-interface packages. Everything implementation-only
  lives under `internal/`; named interfaces are direct children of the module root.
- `post` public contracts:
  - `post::post-commands`: `PostCommandService`, create/update/delete commands, and `PostPatch` (protocol-neutral
    replace/add/remove/removeAll delta).
  - `post::post-query`: authenticated/internal reads (`PostQueryService`, `PostStateView`). `list` returns state views;
    protocol-specific projection (for example Micropub `q=source` property filtering) belongs in the adapter.
  - `post::post-metadata`: protocol-neutral `PostTypeView`/`PostPropertyView` metadata. Micropub maps these to its
    `q=config`/`q=properties` wire shape.
  - `post::post-syndication`: target definitions for Micropub.
  - `post::post-read`: public API reads.
  - `post::post-events`, `post::post-types`.
  - Content URL generation lives in `url/ContentUrlService` and operates on slug/date primitives. The `url` module
    has no post-domain dependency; `post/internal/url/PostUrlExtensions.kt` maps the `Post` domain to those primitives.
- `media` is a supporting module with only `media::media-storage` public. S3 configuration/client and upload
  implementation stay in `media/internal`; the `/micropub/media` endpoint stays in Micropub because it uses Micropub
  token scopes.
- `micropub` has no public surface. Its `internal/command` and `internal/query` packages are protocol adapters over
  post commands/queries/metadata/syndication plus `media::media-storage`. Post updates are sent as `PostPatch`; the
  adapter does not merge the document itself.
- `indieauth` exposes `indieauth::indieauth-token-query` and `indieauth::indieauth-scopes`. `AccessTokenQueryService`
  resolves only tokens for the configured Bastion identity. Token exchange/refresh/revocation commands stay internal.
- `webmention` exposes only `webmention::webmention-read`. Inbound and outbound commands stay internal. It uses
  `url::ContentUrlMatcher` for own-content checks, not `post::post-url`.
- `api` exposes nothing; it depends only on `post::post-read` and `webmention::webmention-read`.
- `config` exposes `config::config-security`; this includes the permissive `publicCorsConfigurationSource` used by
  IndieAuth and Micropub. Everything else is internal. The Micropub multipart filter lives in Micropub, not `config`.
- `PostReadService` owns public feed/lookup/tag/syndication read behavior, including query parsing, date ranges, and
  pagination validation. `api` controllers only translate HTTP params and map `PostView`/`PostLookup` to response DTOs.
- `WebmentionReadService` owns webmention read views and per-post counts. `api` maps its views to response DTOs and does
  not import internal webmention types.
- `PostEntity.version` is a JPA optimistic-lock column. `PostCommandService.update/delete` translate stale writes to
  `PostCommandConflictException`; Micropub maps that to 400 `invalid_request` because Micropub clients do not
  understand 409. Keep the command methods transactional so the entity is loaded and flushed in one persistence
  context. Do not expose the version or ETag yet.

## Post types and discovery

- Post types and discovery rules are declarative in `post/internal/types`; adding or removing a type is a YAML change.
  The bundled default is `src/main/resources/post-types-bundled.yaml` (strict IndieWeb h-entry PTD). An external
  `bastion.post-types` configuration replaces it entirely; the catalog never merges the two. Detection uses the
  presence of any enumerable `bastion.post-types.*` property, so a partial external file fails fast instead of
  silently falling back.
- Do not name the bundled file `post-types-default.yaml` while tests import `classpath:post-types.yaml`: Spring's
  config-data import also resolves the default-profile variant `post-types-default.yaml`, which shadows the external
  file. `post-types-bundled.yaml` avoids that collision.
- Discovery is per root. `bastion.post-types.roots` is a map of mf2 root (`entry`, `event`, `h-` prefix optional) to
  `{fallback, rules}`. A post uses the rules and fallback of its primary root (first `type` entry, same as the stored
  `h` column); a root with no block gets no type. Rules are ordered, first match wins. A rule or fallback must target a
  type whose `h` equals its root, or startup fails. The bundled default only defines `entry`. The vocabulary is
  `property` with `text`/`url`/`one-of`/`object-has`, `name-prefix-of`, and `all`/`any`/`not`. `url` requires an
  absolute http(s) URI.
- Spring's binder silently ignores a property whose declared type is the enclosing type. `not` therefore binds to
  `PostTypesConfig.NegatedCondition`, which converts back to a `RuleCondition`; do not replace it with a direct
  self-reference.
- YAML 1.1 parses unquoted `yes`/`no` as booleans. Quote RSVP values and any other literal in `one-of`/option lists
  (`["yes", "no", ...]`).
- `posts.type` is a normal indexed column now, not a generated expression. `PostService.create/updatePost` classify and
  write it; `PostTypeReclassificationService` backfills existing rows through `PostTypeBackfillWriter` (JDBC, batches,
  no events, no syndication or webmention side effects). Its fingerprint covers each root's fallback and rules (sorted by root) only, not
  syndication formats or type metadata. State lives in `post_type_state`; the fixed JobRunr job
  `post-type-reclassification` runs every 15 minutes and a claim row serializes replicas.
- Keep `jsonb_element_text` in the database: `post_categories` (V11) still calls it even though the post type discovery
  functions are gone. `V22` drops the other discovery helpers.

## Module events

- `post` owns post-change events: `PostService` publishes `PostChangedEvent` (`post/events/`) from `create` and
  `updatePost`, classifying delete/restore. The event is intentionally thin (`postId`, change type, before/after feed
  visibility); consumers load the latest post instead of applying an event delta, so duplicate or out-of-order delivery
  converges on current state.
- Event listeners use `@ApplicationModuleListener`, which is async and includes `AFTER_COMMIT` plus
  `REQUIRES_NEW`. The `REQUIRES_NEW` is required: after-commit listeners still run with the completed transaction bound,
  so writes made without a new transaction silently do not persist (and JobRunr enqueues can be lost). Listener methods
  must not catch exceptions; the event publication registry marks failures `FAILED` so the resubmission job can retry
  them.
- `spring-modulith-starter-jdbc` owns the `event_publication` table. Modulith's JDBC schema initializer creates it at
  startup, like JobRunr owns its tables; do not add a Flyway migration. `ddl-auto: validate` does not see it.
- `@EnableAsync` on `BastionApplication` is required for `@ApplicationModuleListener`. The `spring.task.execution` pool
  is bounded so event bursts cannot create unbounded threads.
- Multi-instance event recovery: `republish-outstanding-events-on-restart=false` prevents every replica from
  republishing all incomplete publications during rolling deploys or autoscaling. The staleness monitor marks stuck
  `PUBLISHED`, `PROCESSING`, and `RESUBMITTED` publications `FAILED`. The JobRunr recurring
  `event-publication-resubmission` job has a fixed ID, so only one instance runs it per occurrence; it resubmits
  failed publications older than 2 minutes, up to 5 completion attempts. Publications at the attempt limit remain
  `FAILED` for actuator inspection.
- `completion-mode=delete` keeps the event table bounded.
- Syndication dispatch uses claim tokens stored on `post_syndications`. Each claim becomes the JobRunr job ID, so the
  event path and periodic reconciliation cannot dispatch the same operation concurrently. Claims expire after 5
  minutes, and a recurring `syndication-reconciliation` job repairs failed, missing, or stale copies every 15 minutes
  using `last_synced_updated`.
- Async listener tests must await delivery with Awaitility or Mockito timeout. Integration tests can inspect
  `event_publication` for in-flight or `FAILED` rows; with `completion-mode=delete`, completed rows are removed.

## Scaling and multi-instance

- Bastion's HTTP tier is stateless: no sticky sessions, IndieAuth state and tokens in Postgres, media in S3, webmention
  endpoint cache in Postgres. Multiple replicas can share one database behind a load balancer.
- Webmention submission limiting is Redis-backed through Bucket4j. A dedicated Lettuce connection uses
  `BASTION_REDIS_URI` and `BASTION_REDIS_DATABASE`, plus optional `BASTION_REDIS_USERNAME`,
  `BASTION_REDIS_PASSWORD`, `BASTION_REDIS_SSL`, and `BASTION_REDIS_TIMEOUT`, so the 10-per-source-per-hour and
  30-second pair limits are global across replicas. When Redis is shared, set a non-zero database number and keep the
  `BASTION_WEBMENTION_RATE_LIMIT_KEY_PREFIX` namespace. Bucket keys expire through Redis TTL; there is no cleanup job.
- JobRunr runs a background job server on every replica by default. `JOBRUNR_WORKER_COUNT` bounds per-replica worker
  concurrency and `JOBRUNR_POLL_INTERVAL_SECONDS` controls polling. All recurring jobs use fixed IDs, so JobRunr runs
  one occurrence cluster-wide. Decide whether the dashboard is exposed on one admin replica or disabled publicly.
- Size Hikari for `replicas * pool-size` against Postgres `max_connections`.
- A two-replica smoke test should verify: a post created on one replica processes its side effects, a replica killed
  after commit is recovered by the other replica's staleness/resubmission path, the webmention limiter is global, and
  syndication dispatch is not duplicated.
- Observe failed event publications through the Modulith actuator/observability endpoints and JobRunr failures through
  its dashboard or logs. Read projections for API composition remain deferred until Webmention or syndication becomes
  remote.
- Webmention reconciliation (`WebmentionService.reconcile`) is idempotent: it compares notifications by `post_id`,
  current source URL, and currently linked targets, then activates, retracts, or silently inactivates as needed.
- WebSub pings when the public feed changes (`Post.isFeedVisible`: published + PUBLIC + not deleted). This includes
  demotions and deletions, but not draft or UNLISTED edits, because UNLISTED posts are reachable by URL but absent from
  the public feed.

## IndieAuth

- Bastion is its own IndieAuth provider (`dev.jacobandersen.bastion.indieauth`): no local accounts, GitHub is the only
  identity provider, the browser UI is delegated to the "Herald" service via `bastion.indieauth.herald.*`.
- Server metadata (`/.well-known/oauth-authorization-server`, `type/AuthorizationServerMetadata.kt`) advertises the
  `issuer` (trailing-slash normalized, https-only via `util/Issuers.kt`), token/introspection/revocation/userinfo
  endpoints, `service_documentation`, and `authorization_response_iss_parameter_supported=true`. The metadata URL
  itself is discovered via the `indieauth-metadata` link relation published manually on the external `me` site
  (Bastion serves no profile pages); see `DiscoveryController.kt`.
- The authorization redirect carries `code` + `state` + `iss` (`util/Redirects.kt`); `iss` must equal the metadata
  issuer for mix-up protection.
- Code redemption is split per spec: POST `/indieauth/auth` returns `{me}` only (`ProfileUrlController` +
  `ProfileUrlService`), POST `/indieauth/token` returns tokens (`TokenController` + `AccessTokenCommandService`) and
  rejects empty-scope codes with `invalid_grant`. Token responses carry `expires_in` and rotate a `refresh_token`
  (`grant_type=refresh_token`, same-or-narrower scope, single-use rotation via `RefreshTokenService` and the
  `indieauth_refresh_tokens` table, TTLs `access-token-ttl` 30d / `refresh-token-ttl` 90d).
- Introspection (`/indieauth/introspect`, Bearer-gated, RFC 7662 plus `me`), revocation (`/indieauth/revocation`,
  always 200), and userinfo (`/indieauth/userinfo`, needs `profile`/`email` scope) are served and advertised.
  Profile claims come from static `bastion.indieauth.profile.*` config (`ProfileClaimService`); `email` needs both
  `profile` and `email` scopes.
- IndieAuth owns the standard `profile` and `email` scopes. Modules register their own scopes by implementing
  `IndieAuthScopeContributor` from `indieauth::indieauth-scopes`; `ScopeCatalog` unions the standard and contributed
  scopes and is the single source for discovery and authorization validation. Micropub contributes its scopes from
  `MicropubTokenScope`. There is no central scope list in config. A module that contributes a scope must enforce it,
  and new modules should use module-prefixed scope names such as `notes:read`.
- Micropub token validation (`MicropubTokenValidator`) uses `AccessTokenQueryService` from
  `indieauth::indieauth-token-query`; the query service enforces the configured `bastion.indieauth.me`, so Micropub does
  not read that config directly. Token commands stay inside IndieAuth.
- Raw `state`/`code`/`access_token`/`refresh_token` values are never persisted - only their SHA-256 digests
  (`indieauth/internal/security/Tokens.kt`). PKCE is lenient for max client compat (deprecated): a missing `code_challenge`
  is accepted with a warning, S256-only when present, and the redemption enforces the conditional rule (no challenge
  means no verifier, challenge means a matching verifier is required).
- Client validation (`indieauth/internal/util/IndieAuthUrls.kt`, `indieauth/internal/service/ClientMetadataFetcher.kt`) enforces strict profile/client URL
  rules and the 4.2.2 cross-host redirect allowlist (JSON `redirect_uris`, `Link rel=redirect_uri`, HTML link tags).
  Loopback clients are never fetched; inconclusive fetches allow with a warning (fail-open), a fetched allowlist
  missing the target blocks.
- Adding a provider = one new `IdentityProvider` implementation plus config.

## Syndication

- Syndication now lives in `post/internal/syndication`; `micropub` only exposes target choices and sends requested
  target UIDs through `PostCommandService`. The config prefix remains `bastion.micropub.syndication.targets` for
  deployment compatibility.
- `PostCommandService` syncs desired target rows (`post_syndications.desired`), and `PostChangedEvent` triggers
  `PostSyndicationListener` -> `SyndicationService.reconcile(forceUpdate = true)`. The listener is an
  `@ApplicationModuleListener`.
- `post_syndications` stores the canonical `source_url`, `last_synced_updated`, and dispatch claim fields
  (`pending_action`, `pending_since`, `pending_token`). Before enqueueing, `SyndicationService` claims the row and uses
  the claim token as the JobRunr job ID, so the event path and periodic reconciliation cannot dispatch the same
  operation concurrently. Jobs clear the claim in `finally`; claims older than 5 minutes can be reclaimed.
- The recurring `syndication-reconciliation` JobRunr job runs every 15 minutes and reconciles each post with
  `forceUpdate = false`, retrying missing, failed, or stale copies by comparing `last_synced_updated` with the post's
  current `updated` value.
- Reconciliation creates missing copies, sends excerpt updates for existing copies, rebases when the post URL moved, and
  retracts copies when the post is deleted, non-public, or the target is no longer desired. `markRetracted` clears the
  held-copy state; a row with `desired=false` and no held copy is removed on the next reconcile.
- Dispatch jobs are best-effort `@Job(retries = 0)` and never fail the post command. The periodic reconciliation is the
  retry path. Non-public posts are never sent.
- The downstream copy is rendered from the post type's configured syndication template (`SyndicationTextRenderer.kt`
  + `SyndicationContentMapper.kt`). `bastion.post-types.defaults.syndication` supplies the base format; a type's
  `syndication` block overrides fields individually. `overflow-format` defaults to `{url}`, and `fallback-format` is
  required whenever the effective format contains `{name}` and must not itself use `{name}`. `maxGraphemes` counts
  grapheme clusters; the permalink is never dropped and `summary` is overwritten with the same text so full content
  cannot leak.
- `{excerpt}` is configurable via `syndication.excerpt` (`sources`, `ellipsis`, `truncate`, `min-graphemes`), merged
  field by field from defaults then the type (`ExcerptConfig.resolve`). Sources are mf2 properties tried in order
  (HTML-only values are stripped with Jsoup); a source already shown by a template field is skipped. Besides
  `{excerpt}`/`{url}`, placeholders are fields (`{name}`, `{summary}`, `{published}`, `{tags}`, `{prop:<property>}`)
  that may be missing per post: any missing field switches to `fallback-format` (which may not use fields). Only the
  excerpt is truncated; oversized field values push the post to `overflow-format`.
- Updates use `SyndicationHttpClient.serializeExcerptUpdate`: a full `content`/`summary`/`url` (and article `name`)
  replace built from current post state, not the original Micropub delta. This keeps reconciliation idempotent.
- `mp-syndicate-to` is translated by Micropub into a protocol-neutral `SyndicationTargetChange`
  (`replace`/`add`/`remove`); the post command applies it to the current desired target set.

## Webmention

- Outbound notification state is reconciled from post events (`PostWebmentionListener` ->
  `WebmentionService.reconcile`). Notifications are keyed to a post via `webmention_notifications.post_id`; a source
  URL that no longer matches the post retracts delivered notifications (fixes slug moves) and silently inactivates
  undelivered ones. `refresh` re-sends to delivered targets on updates; deletions only retract.
- Submission limiting uses Bucket4j buckets in Redis: 10 per source per hour and a 30-second source/target pair
  cooldown. The pair bucket is consumed first so a rejected duplicate pair does not spend a source token. Redis errors
  propagate and fail the request closed rather than allowing unlimited submissions. Configure the connection with
  `BASTION_REDIS_*`; use a non-zero `BASTION_REDIS_DATABASE` when sharing Redis.
- Salmention:
  - Received responses are threaded when the API reads them (`WebmentionThreadResolver`), not stored as a parent
    column, so out-of-order arrivals thread correctly. The parent is the response whose source URL or `u-url` is in
    `in_reply_to`.
  - Nested responses a verified source displays (`comment` values and nested h-entry/h-cite) are stored as their own
    rows with `via_id`. A directly received webmention always wins and is never overwritten by a nested copy.
  - A row's identity for dedup, takeover, and deletion is its source URL plus its `u-url` only when that shares the
    source's origin (`ReceivedWebmentionAnalyzer.identityUrls`), so a webmention cannot suppress another site's
    response. Tradeoff: a cross-origin proxy reply (Bridgy) that is also displayed nested can appear twice. Threading
    also indexes cross-origin `u-url`s, but only as lowest-priority fallback keys, so Bridgy reply chains thread while a
    spoofer can only capture URLs no other row claims; moderation is the future remedy for bad actors. The API exposes
    `urlSameOrigin` so the frontend can tell a verified `url` from a source's cross-origin claim.
  - `display_fingerprint` survives `ensurePending`. Only a real change in what the post displays enqueues
    `SalmentionService.resendAfterResponseChange`, which runs `reconcile(refresh = true)` to re-ping the post's
    delivered targets upstream. This gate is what stops ping-pong loops.
  - The frontend must render received replies as nested `p-comment h-cite` (with `u-url`/`u-in-reply-to`) inside the
    post's `h-entry`, or upstream sites see nothing new.
- Self-webmentions are suppressed: `ContentUrlMatcher.isOwnContentUrl` (host + port match on `bastion.content.base-url`,
  lenient on scheme) filters targets in `WebmentionService.targetUrlsOf` plus a guard in `sendWebmention`, and
  `WebmentionController` rejects sources on the own content domain.
