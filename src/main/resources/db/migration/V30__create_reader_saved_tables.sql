-- Saved folders of the reader API: a private, owner-curated store of frozen copies of entries. There is deliberately
-- no foreign key to microsub_* tables, so nothing in the microsub entry lifecycle can reach a saved item.
create table reader_saved_folders
(
    id         uuid primary key,
    uid        text        not null unique,
    name       text        not null,
    parent_id  uuid references reader_saved_folders (id) on delete cascade,
    position   integer     not null,
    version    bigint      not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

-- Sibling folders have unique names (ignoring case); `position` orders the siblings of one parent.
create unique index uq_reader_saved_folder_name
    on reader_saved_folders (coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
create index idx_reader_saved_folder_parent on reader_saved_folders (parent_id);

create table reader_saved_items
(
    id                 uuid primary key,
    folder_id          uuid        not null references reader_saved_folders (id) on delete cascade,
    dedupe_key         text        not null,
    origin_channel_uid text,
    origin_entry_id    uuid,
    origin_feed_url    text,
    url                text,
    author_url         text,
    published          timestamptz not null,
    categories         text[]      not null default '{}',
    kinds              text[]      not null default '{}',
    data               jsonb       not null,
    search             tsvector,
    note               text,
    saved_at           timestamptz not null,
    updated_at         timestamptz not null,
    constraint uq_reader_saved_folder_key unique (folder_id, dedupe_key)
);

create index idx_reader_saved_list on reader_saved_items (folder_id, saved_at desc, id desc);
create index idx_reader_saved_all on reader_saved_items (saved_at desc, id desc);
create index idx_reader_saved_search on reader_saved_items using gin (search);
create index idx_reader_saved_origin on reader_saved_items (origin_entry_id);
