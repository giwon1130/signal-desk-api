-- Sign in with Apple — Apple sub(고유 사용자 ID) 저장. google_id/kakao_id 와 동일 패턴.
alter table signal_desk_users add column if not exists apple_id text;
create unique index if not exists ux_signal_desk_users_apple_id on signal_desk_users(apple_id);
