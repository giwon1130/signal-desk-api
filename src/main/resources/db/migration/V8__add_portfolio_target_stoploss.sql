-- 보유 종목에 목표가 / 손절가 컬럼 추가 (선택 입력, 없으면 NULL)
alter table signal_desk_portfolio_positions
    add column if not exists target_price    integer default null,
    add column if not exists stop_loss_price integer default null;
