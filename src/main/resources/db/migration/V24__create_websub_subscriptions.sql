create table websub_subscriptions
(
    id               uuid primary key,
    topic            text        not null,
    hub              text        not null,
    secret           text        not null,
    state            text        not null,
    lease_seconds    bigint,
    lease_expires_at timestamptz,
    requested_at     timestamptz,
    verified_at      timestamptz,
    last_notified_at timestamptz,
    last_error       text,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    constraint uq_websub_subscription_topic_hub unique (topic, hub)
);

create index idx_websub_subscription_state_lease on websub_subscriptions (state, lease_expires_at);
