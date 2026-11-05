alter table received_webmentions
    add column url text,
    add column in_reply_to text[],
    add column via_id uuid references received_webmentions (id) on delete cascade,
    add column display_fingerprint text;

create index idx_received_webmention_via on received_webmentions (via_id);
