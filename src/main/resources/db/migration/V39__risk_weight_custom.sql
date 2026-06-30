-- 시장 분위기 가중 — PRO 커스텀(지표별 직접 조정) 저장.
-- custom_weights = JSON map (컴포넌트 라벨 → 가중 배수). preset='CUSTOM' 일 때만 적용.
alter table signal_desk_risk_weight_preference
    add column if not exists custom_weights text;
