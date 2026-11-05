alter table microsub_entries add column last_seen_at timestamptz;

update microsub_entries set last_seen_at = updated_at;

alter table microsub_entries alter column last_seen_at set not null;
