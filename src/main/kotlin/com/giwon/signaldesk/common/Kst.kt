package com.giwon.signaldesk.common

import java.time.ZoneId

/**
 * 서버는 UTC(Railway)에서 돈다 — zone 없는 now() 는 KST 00~09시에 하루 어긋난다.
 * 사용자에게 보이는 날짜/시각·KST 기준 판단에는 항상 이 zone 을 명시한다.
 */
val KST: ZoneId = ZoneId.of("Asia/Seoul")
