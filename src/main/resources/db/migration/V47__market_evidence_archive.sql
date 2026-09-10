-- Bounded first-observed inputs + rule output for offline replay. Not prediction accuracy data.
create table if not exists signal_desk_market_evidence (
    bucket_at timestamptz not null,
    rules_version varchar(64) not null,
    observed_at timestamptz not null,
    inputs jsonb not null,
    analysis jsonb not null,
    primary key (bucket_at, rules_version)
);
