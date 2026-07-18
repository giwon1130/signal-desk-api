package com.giwon.signaldesk.features.rounds.presentation

import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.rounds.application.MarketRound
import com.giwon.signaldesk.features.rounds.application.MarketRoundContent
import com.giwon.signaldesk.features.rounds.application.MarketRoundContentDraft
import com.giwon.signaldesk.features.rounds.application.MarketRoundDraft
import com.giwon.signaldesk.features.rounds.application.MarketRoundService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/market-rounds")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MarketRoundController(private val service: MarketRoundService) {
    @GetMapping("/active")
    fun active(): ApiResponse<MarketRoundResponse?> = ApiResponse(true, service.active()?.let(MarketRoundResponse::from))
}

/** 운영자가 검수한 링크만 게시한다. 자동 유튜브 자막 수집/생성은 이 경로에서 하지 않는다. */
@RestController
@RequestMapping("/api/v1/admin/market-rounds")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AdminMarketRoundController(
    private val service: MarketRoundService,
    private val authContext: AuthContext,
    private val adminGuard: AdminGuard,
) {
    @PostMapping
    fun save(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestBody request: MarketRoundUpsertRequest,
    ): ApiResponse<MarketRoundResponse> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        return ApiResponse(true, MarketRoundResponse.from(service.save(request.toDraft())))
    }
}

data class MarketRoundUpsertRequest(
    val id: String = "",
    val title: String,
    val summary: String,
    val riskLevel: String = "CAUTION",
    val marketScope: String = "BOTH",
    val checkpoints: List<String> = emptyList(),
    val startsAt: String,
    val endsAt: String,
    val contents: List<MarketRoundContentUpsertRequest> = emptyList(),
) {
    fun toDraft() = MarketRoundDraft(
        id = id, title = title, summary = summary, riskLevel = riskLevel.uppercase(), marketScope = marketScope.uppercase(),
        checkpoints = checkpoints, startsAt = Instant.parse(startsAt), endsAt = Instant.parse(endsAt),
        contents = contents.map(MarketRoundContentUpsertRequest::toDraft),
    )
}

data class MarketRoundContentUpsertRequest(
    val kind: String = "VIDEO",
    val sourceName: String,
    val expertName: String? = null,
    val title: String,
    val url: String,
    val publishedAt: String? = null,
    val whyRecommended: String,
    val label: String = "시장 해설",
    val official: Boolean = true,
) {
    fun toDraft() = MarketRoundContentDraft(
        kind = kind, sourceName = sourceName, expertName = expertName, title = title, url = url,
        publishedAt = publishedAt?.takeIf(String::isNotBlank)?.let(Instant::parse), whyRecommended = whyRecommended,
        label = label, official = official,
    )
}

data class MarketRoundResponse(
    val id: String,
    val title: String,
    val summary: String,
    val riskLevel: String,
    val marketScope: String,
    val checkpoints: List<String>,
    val startsAt: String,
    val endsAt: String,
    val contents: List<MarketRoundContentResponse>,
) {
    companion object {
        fun from(round: MarketRound) = MarketRoundResponse(
            id = round.id, title = round.title, summary = round.summary, riskLevel = round.riskLevel,
            marketScope = round.marketScope, checkpoints = round.checkpoints, startsAt = round.startsAt.toString(),
            endsAt = round.endsAt.toString(), contents = round.contents.map(MarketRoundContentResponse::from),
        )
    }
}

data class MarketRoundContentResponse(
    val id: String,
    val kind: String,
    val sourceName: String,
    val expertName: String?,
    val title: String,
    val url: String,
    val publishedAt: String?,
    val whyRecommended: String,
    val label: String,
    val official: Boolean,
) {
    companion object {
        fun from(content: MarketRoundContent) = MarketRoundContentResponse(
            id = content.id, kind = content.kind, sourceName = content.sourceName, expertName = content.expertName,
            title = content.title, url = content.url, publishedAt = content.publishedAt?.toString(),
            whyRecommended = content.whyRecommended, label = content.label, official = content.official,
        )
    }
}
