-- 유튜브 데일리 방송(삼프로TV 등) 요약 + 흐름 분석 결과를 저장한다.
-- 동일 video_id 재처리 방지를 위해 unique 제약.
create table if not exists signal_desk_media_summaries (
    id                 varchar(64)  primary key,
    channel_id         varchar(64)  not null,
    channel_title      varchar(255) not null,
    video_id           varchar(32)  not null unique,
    video_title        varchar(512) not null,
    video_url          text         not null,
    published_at       timestamptz  not null,
    transcript_length  integer      not null default 0,
    summary            text         not null,
    flow_analysis      text         not null,
    key_tickers        text         not null default '',  -- 콤마 구분 (e.g. "005930,000660")
    sentiment          varchar(16)  not null default 'NEUTRAL', -- BULLISH | BEARISH | NEUTRAL
    has_transcript     boolean      not null default false,
    created_at         timestamptz  not null default now()
);

create index if not exists idx_media_summaries_published
    on signal_desk_media_summaries (published_at desc);

create index if not exists idx_media_summaries_channel_published
    on signal_desk_media_summaries (channel_id, published_at desc);
