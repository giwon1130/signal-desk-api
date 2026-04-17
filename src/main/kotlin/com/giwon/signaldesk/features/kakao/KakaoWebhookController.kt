package com.giwon.signaldesk.features.kakao

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/kakao")
class KakaoWebhookController(
    private val skillService: KakaoSkillService,
) {

    /**
     * 카카오 i 오픈빌더 스킬 서버 엔드포인트.
     * 카카오 디벨로퍼스 > 오픈빌더 > 스킬 서버 URL 에 이 경로를 등록한다.
     */
    @PostMapping("/webhook")
    fun webhook(@RequestBody request: KakaoSkillRequest): KakaoSkillResponse =
        skillService.handle(request.userRequest.utterance)
}
