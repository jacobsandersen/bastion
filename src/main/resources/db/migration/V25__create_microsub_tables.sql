create table microsub_channels
(
    id         uuid primary key,
    uid        text        not null unique,
    name       text        not null,
    position   integer     not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

insert into microsub_channels (id, uid, name, position, created_at, updated_at)
values (gen_random_uuid(), 'notifications', 'Notifications', 0, now(), now()),
       (gen_random_uuid(), 'home', 'Home', 1, now(), now());

create table microsub_feeds
(
    id                     uuid primary key,
    channel_id             uuid        not null references microsub_channels (id) on delete cascade,
    url                    text        not null,
    fetch_url              text        not null,
    format                 text        not null,
    name                   text,
    photo                  text,
    description            text,
    hub                    text,
    self_url               text,
    websub_subscription_id uuid,
    etag                   text,
    last_modified          text,
    next_fetch_at          timestamptz not null,
    last_fetched_at        timestamptz,
    last_error             text,
    failure_count          integer     not null default 0,
    claimed_until          timestamptz,
    fetch_started_at       timestamptz,
    refresh_requested_at   timestamptz,
    created_at             timestamptz not null,
    constraint uq_microsub_feed_channel_url unique (channel_id, url)
);

create index idx_microsub_feed_next_fetch on microsub_feeds (next_fetch_at);
create index idx_microsub_feed_websub on microsub_feeds (websub_subscription_id);

create table microsub_entries
(
    id         uuid primary key,
    channel_id uuid        not null references microsub_channels (id) on delete cascade,
    feed_id    uuid references microsub_feeds (id) on delete set null,
    source_key text        not null,
    published  timestamptz not null,
    author_url text,
    url        text,
    categories text[]      not null default '{}',
    kinds      text[]      not null default '{}',
    data       jsonb       not null,
    search     tsvector,
    is_read    boolean     not null default false,
    removed_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_microsub_entry_channel_source unique (channel_id, source_key)
);

create index idx_microsub_entry_timeline on microsub_entries (channel_id, published desc, id desc);
create index idx_microsub_entry_feed on microsub_entries (feed_id);
create index idx_microsub_entry_author on microsub_entries (author_url);
create index idx_microsub_entry_search on microsub_entries using gin (search);
create index idx_microsub_entry_categories on microsub_entries using gin (categories);

create table microsub_moderation
(
    id         uuid primary key,
    channel_id uuid references microsub_channels (id) on delete cascade,
    url        text        not null,
    kind       text        not null,
    created_at timestamptz not null
);

create unique index uq_microsub_moderation
    on microsub_moderation (coalesce(channel_id, '00000000-0000-0000-0000-000000000000'::uuid), url, kind);
