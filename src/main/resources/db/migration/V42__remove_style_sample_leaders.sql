-- 실제 리더처럼 노출되던 스타일 예시 계정을 제거한다.
-- 성과 이력이 없는 데모 데이터는 리더 탐색의 신뢰도를 떨어뜨릴 수 있다.
-- reading_call 은 reading_post 삭제 시 FK(on delete cascade)로 함께 삭제된다.
delete from signal_desk_reading_post
where leader_user_id in (
    'a1f40000-0000-4000-8000-000000000004',
    'a1f50000-0000-4000-8000-000000000005',
    'a1f60000-0000-4000-8000-000000000006'
);

delete from signal_desk_reading_follow
where leader_user_id in (
    'a1f40000-0000-4000-8000-000000000004',
    'a1f50000-0000-4000-8000-000000000005',
    'a1f60000-0000-4000-8000-000000000006'
);

delete from signal_desk_reading_leader
where user_id in (
    'a1f40000-0000-4000-8000-000000000004',
    'a1f50000-0000-4000-8000-000000000005',
    'a1f60000-0000-4000-8000-000000000006'
);

delete from signal_desk_users
where id in (
    'a1f40000-0000-4000-8000-000000000004',
    'a1f50000-0000-4000-8000-000000000005',
    'a1f60000-0000-4000-8000-000000000006'
);
