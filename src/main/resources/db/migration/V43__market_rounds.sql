-- 변동성·실적 등 특정 시장 이벤트에만 열리는 읽기 중심 라운드.
-- 자유 게시판/자동 자막 수집과 분리해, 운영자가 검수한 원문 링크만 노출한다.
create table if not exists signal_desk_market_rounds (
    id varchar(80) primary key,
    title varchar(100) not null,
    summary varchar(700) not null,
    risk_level varchar(16) not null check (risk_level in ('WATCH', 'CAUTION', 'HIGH')),
    market_scope varchar(16) not null check (market_scope in ('KR', 'US', 'BOTH', 'GLOBAL')),
    checkpoints text not null default '',
    starts_at timestamptz not null,
    ends_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (ends_at > starts_at)
);
create index if not exists idx_market_rounds_active
    on signal_desk_market_rounds (starts_at desc, ends_at desc);

create table if not exists signal_desk_market_round_contents (
    id varchar(80) primary key,
    round_id varchar(80) not null references signal_desk_market_rounds(id) on delete cascade,
    kind varchar(24) not null default 'VIDEO',
    source_name varchar(80) not null,
    expert_name varchar(80),
    title varchar(300) not null,
    url text not null,
    published_at timestamptz,
    why_recommended varchar(300) not null,
    label varchar(40) not null default '시장 해설',
    official boolean not null default true,
    display_order int not null default 0,
    created_at timestamptz not null default now()
);
create index if not exists idx_market_round_contents_round
    on signal_desk_market_round_contents (round_id, display_order);

-- 첫 라운드는 2026년 7월 반도체 변동성 대응용. 만료 후 자동으로 숨겨진다.
insert into signal_desk_market_rounds
    (id, title, summary, risk_level, market_scope, checkpoints, starts_at, ends_at)
values
    ('semiconductor-volatility-2026-07', '반도체 변동성 라운드',
     '급락 자체보다 원인과 다음 확인 시점을 분리해서 봐요. 영상은 운영자가 검수한 공식 원문으로만 연결합니다.',
     'HIGH', 'BOTH',
     '미국 반도체 지수와 AI 투자 기대 변화\n주요 기업 실적·가이던스\n국내 수급과 원·달러 흐름',
     '2026-07-17T00:00:00+09:00', '2026-07-25T23:59:59+09:00')
on conflict (id) do nothing;

insert into signal_desk_market_round_contents
    (id, round_id, kind, source_name, expert_name, title, url, published_at, why_recommended, label, official, display_order)
values
    ('semiconductor-volatility-2026-07-3pro', 'semiconductor-volatility-2026-07', 'VIDEO', '삼프로TV', '빈센트 · 하나증권 애널리스트',
     '반도체, 정말 꺾였나', 'https://apps.3protv.com/episode/view/45722?p=VIDEO&t=0',
     '2026-07-16T00:00:00+09:00', '현재 반도체 조정의 지속성 여부를 직접 다루는 공식 방송이에요.', '전문가 시장 해설', true, 0)
on conflict (id) do nothing;
