-- 시장 분위기(합성 위험도) 가중 프로파일 — PRO 전용 커스터마이징.
-- preset = RiskWeightPreset enum 이름 (BALANCED/FX_SENSITIVE/RATE_SENSITIVE/DEFENSIVE/AGGRESSIVE).
create table if not exists signal_desk_risk_weight_preference (
    user_id uuid primary key references signal_desk_users(id) on delete cascade,
    preset text not null default 'BALANCED',
    updated_at timestamptz not null default now()
);
