-- 미디어 요약의 출처 분기 (YOUTUBE / NEWS_DIGEST)
-- 기존 데이터는 모두 YouTube 영상 요약이었으므로 기본값 YOUTUBE.
-- 뉴스 종합 요약은 video_id 가 의미 없으므로 가짜 unique 값 ("news-YYYY-MM-DD-MARKET")으로 채워 충돌 방지.
alter table signal_desk_media_summaries
    add column if not exists source varchar(16) not null default 'YOUTUBE';
