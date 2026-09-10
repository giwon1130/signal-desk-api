-- Keep old records, but never mix unversioned/overwritable forecasts into new validation statistics.
alter table signal_desk_premarket_direction_forecast
    add column if not exists rules_version varchar(128) not null default 'legacy-unversioned';
create index if not exists idx_premarket_forecast_version_date
    on signal_desk_premarket_direction_forecast (rules_version, prediction_date);
