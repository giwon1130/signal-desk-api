INSERT INTO signal_desk_portfolio_positions
 (id, market, ticker, name, buy_price, current_price, quantity, profit_amount, evaluation_amount, profit_rate, target_price)
VALUES ('decimal-us', 'US', 'AAPL', 'test', 200.15, 201.25, 0.5, 0.55, 100.625, 0.5496, 210.15);
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM signal_desk_portfolio_positions WHERE id = 'legacy-kr' AND evaluation_amount = 750000 AND quantity = 10)
 THEN RAISE EXCEPTION 'legacy row changed'; END IF;
 IF NOT EXISTS (SELECT 1 FROM signal_desk_portfolio_positions WHERE id = 'decimal-us' AND buy_price = 200.15 AND quantity = 0.5 AND evaluation_amount = 100.625 AND target_price = 210.15)
 THEN RAISE EXCEPTION 'decimal values lost'; END IF;
END $$;
SELECT id, buy_price, quantity, evaluation_amount, target_price FROM signal_desk_portfolio_positions ORDER BY id;
