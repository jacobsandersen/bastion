-- Post type discovery moves into the application. The stored value stays in
-- the indexed `posts.type` column, but it is now written on create/update and
-- by the reclassification job instead of a generated column.

alter table posts alter column type drop expression if exists;

drop function if exists post_type_discovery(jsonb);
drop function if exists jsonb_array_any_checkin(jsonb);
drop function if exists jsonb_array_has_text(jsonb, text);
drop function if exists jsonb_array_first_text(jsonb, text);
drop function if exists jsonb_array_any_url(jsonb, text);
drop function if exists jsonb_element_url(jsonb);
drop function if exists is_valid_url(text);
-- jsonb_element_text is retained: post_categories (V11) still uses it.

create table post_type_state
(
    id                  boolean primary key default true check (id),
    desired_fingerprint text        not null,
    applied_fingerprint text,
    running_since       timestamptz,
    started_at          timestamptz,
    completed_at        timestamptz,
    processed_count     bigint      not null default 0,
    changed_count       bigint      not null default 0
);
