-- profit_amount, evaluation_amount: Int 오버플로우 방지를 위해 bigint로 변환
ALTER TABLE signal_desk_portfolio_positions ALTER COLUMN profit_amount TYPE bigint;
ALTER TABLE signal_desk_portfolio_positions ALTER COLUMN evaluation_amount TYPE bigint;
