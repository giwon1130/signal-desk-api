# Context

## endpoints
- `GET /api/v1/market/summary`
- `GET /api/v1/market/sections`
- `GET /api/v1/market/ai-recommendations`

## notes
- web/app에서 CORS 접근 허용 설정 반영됨
- signal-desk-web, signal-desk-app과 동일 데이터 계약 사용

## references
- suite root: `/Users/g/workspace/public/finance/signal-desk`
- global map: `/Users/g/context.md`

## 2026-10-16 memory / precomputation optimization

- User requested reducing Railway API memory and unused precomputation while preserving briefs/alerts. Repository was clean, `git pull --ff-only origin main` up to date at4d31997.
- Railway read-only baseline: current cycle09-23~10-23 total usage$17.25, memory$17.02; Signal Desk API$6.61 (memory$6.58), DB$0.62. Whole-workspace estimated cycle$22.92, Pro minimum$20. API7-day hourly average0.886GB, before-change1h average938.5MB; CPU0.0016vCPU. API/DB one replica each, private DB network, general warmer already off. No raw credentials/logs stored. Direct SSH unavailable due no registered keys; no SSH access was created.
- Docker keeps Java21/G1/Xms128m/Xmx768m and adds idle concurrent periodic collection60s, explicit system-load threshold0, free heap ratio10/30. Peak heap headroom retained; container/DB limits unchanged. Primary docs are linked in docs/운영/비용최적화.md.
- SectorRotationWarmupScheduler is opt-in (`SIGNAL_DESK_SECTOR_ROTATION_WARM_ENABLED=false`), so no boot/daily18-ETF long-history precompute by default. MoverReasonService on-demand behavior retained; former15min regular-session prefetch moved into opt-in MoverReasonWarmupScheduler (`SIGNAL_DESK_MOVER_REASON_WARM_ENABLED=false`). Current production has no override for either short or relaxed-binding property name. Public endpoints and data quality/freshness rules unchanged. First uncached optional screen load may be slower.
- Yahoo long-history cache bounded by60,000 total bars, not60 entries; full history is still used for calculations. All registered Caffeine caches use shared systemScheduler for expired-object cleanup without requests/upstream work. Existing TTLs retained. Briefs/alerts/disclosure/snapshot/seasonality-rule schedules, scheduler pool6/http pool12/core pool8, market guards and dedup unchanged. Railway Serverless remains off; local trader/app/DB contents are out of scope.
- Java21 offline `test bootJar` succeeded:387 tests total,386 passed,0 failures/errors,1 existing SnapshotPostgresIntegrationTest skipped (no dedicated test DB). Added behavior tests for optional warmers disabled/enabled, morning/KR-close/US-close and KR/US watchlist/reading dispatch, request-driven sector report + cache reuse, history retention budget. Existing evidence briefing/failure fallback and push text tests pass. Main push follows repo auto-deploy workflow; verify Railway latest commit, /health jdbc/db up, public summary/recent briefs, sanitized startup/scheduler errors and post-start memory before reporting deployment success.

