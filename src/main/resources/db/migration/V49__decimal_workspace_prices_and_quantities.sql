-- Preserve existing values; no destructive rewrite or inferred recovery of already rounded data.
ALTER TABLE signal_desk_watchlist
    ALTER COLUMN price TYPE numeric(30,8),
    ALTER COLUMN alert_below TYPE numeric(30,8),
    ALTER COLUMN alert_above TYPE numeric(30,8);
ALTER TABLE signal_desk_portfolio_positions
    ALTER COLUMN buy_price TYPE numeric(30,8),
    ALTER COLUMN current_price TYPE numeric(30,8),
    ALTER COLUMN quantity TYPE numeric(30,8),
    ALTER COLUMN target_price TYPE numeric(30,8),
    ALTER COLUMN stop_loss_price TYPE numeric(30,8),
    ALTER COLUMN profit_amount TYPE numeric(38,16),
    ALTER COLUMN evaluation_amount TYPE numeric(38,16);
ALTER TABLE signal_desk_ai_track_records
    ALTER COLUMN entry_price TYPE numeric(30,8),
    ALTER COLUMN latest_price TYPE numeric(30,8);
