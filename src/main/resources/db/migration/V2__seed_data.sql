insert into signal_desk_watchlist (id, market, ticker, name, price, change_rate, sector, stance, note)
values
    ('watch-kr-samsung', 'KR', '005930', '삼성전자', 84200, 1.82, '반도체', '분할 접근', '수급 회복과 뉴스 모멘텀 확인'),
    ('watch-us-nvda', 'US', 'NVDA', 'NVIDIA', 912, 2.41, 'AI 반도체', '추세 추종', '실적 시즌 전후 변동성 주의')
on conflict (id) do nothing;

insert into signal_desk_portfolio_positions (id, market, ticker, name, buy_price, current_price, quantity, profit_amount, evaluation_amount, profit_rate)
values
    ('portfolio-kr-sk', 'KR', '000660', 'SK하이닉스', 178000, 186500, 12, 102000, 2238000, 4.78)
on conflict (id) do nothing;

insert into signal_desk_paper_positions (id, market, ticker, name, average_price, current_price, quantity, return_rate)
values
    ('paper-us-meta', 'US', 'META', 'Meta', 502, 516, 8, 2.79)
on conflict (id) do nothing;

insert into signal_desk_paper_trades (id, trade_date, side, market, ticker, name, price, quantity)
values
    ('trade-us-meta-buy', '2026-04-09', 'BUY', 'US', 'META', 'Meta', 502, 8)
on conflict (id) do nothing;

insert into signal_desk_ai_picks (id, market, ticker, name, basis, confidence, note, expected_return_rate)
values
    ('ai-kr-solar', 'KR', '009830', '한화솔루션', '수급 반전 + 뉴스 군집 호재', 78, '정책/원자재 뉴스 확인 필요', 7.5)
on conflict (id) do nothing;

insert into signal_desk_ai_track_records (id, recommended_date, market, ticker, name, entry_price, latest_price, realized_return_rate, success)
values
    ('track-us-msft', '2026-04-01', 'US', 'MSFT', 'Microsoft', 418, 431, 3.11, true)
on conflict (id) do nothing;
