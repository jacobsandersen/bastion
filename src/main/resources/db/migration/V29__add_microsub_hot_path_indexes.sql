-- Unread counts (action=channels, per-feed totals) only look at live unread rows, which are a small share of the table.
create index idx_microsub_entry_unread on microsub_entries (channel_id, feed_id, author_url)
    where not is_read and removed_at is null;

-- Timeline paging never returns deleted entries, so the arrival index leaves them out.
drop index idx_microsub_entry_arrival;
create index idx_microsub_entry_arrival on microsub_entries (channel_id, created_at desc, published desc, id desc)
    where removed_at is null;
