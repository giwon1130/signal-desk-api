-- 야간 방향성 예측·실제 시초 결과 이력. 장전 신호의 적중률과 가중치 보정 근거로 보존한다.
create table if not exists signal_desk_premarket_direction_forecast (
    prediction_date       date primary key,
    recorded_at           timestamptz not null default now(),
    bias                  varchar(16),
    score                 double precision,
    confidence            varchar(16),
    coverage              int,
    input_count           int,
    inputs                jsonb not null default '[]'::jsonb,
    previous_close        double precision,
    actual_open           double precision,
    actual_gap_rate       double precision,
    actual_bias           varchar(16),
    correct               boolean,
    evaluated_at          timestamptz
);

create index if not exists idx_premarket_direction_forecast_evaluated
    on signal_desk_premarket_direction_forecast (evaluated_at desc);
