package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

@Service
class AlternativeSignalService(
    private val pizzIntClient: PizzIntClient,
    private val venueSignalCollector: VenueSignalCollector,
) {

    fun buildAlternativeSignals(): List<AlternativeSignal> {
        val snapshot = pizzIntClient.fetchSignals()
        val venueSnapshot = venueSignalCollector.collect()

        if (snapshot == null) {
            return listOf(
                AlternativeSignal(
                    label = "Pentagon Pizza Index", score = 58, state = "관측 대기",
                    note = "PizzINT 공개 페이지 기반 실험 지표. 외부 응답 실패 시 기본값으로 표시",
                    highlights = listOf("DOUGHCON 대기", "외부 응답 fallback"),
                    source = "PizzINT", url = "https://www.pizzint.watch/", experimental = true,
                    description = PIZZA_DESCRIPTION, methodology = PIZZA_METHODOLOGY,
                ),
                AlternativeSignal(
                    label = "Policy Buzz", score = 52, state = "보통",
                    note = "정책/안보 키워드 OSINT 강도와 뉴스 밀도를 함께 볼 예정",
                    highlights = listOf("OSINT feed 대기", "스파이크 평균 대기"),
                    source = "PizzINT + News", url = "https://www.pizzint.watch/whitepaper", experimental = true,
                    description = POLICY_DESCRIPTION, methodology = POLICY_METHODOLOGY,
                ),
                AlternativeSignal(
                    label = "Bar Counter-Signal", score = 44, state = "관측 대기",
                    note = "Freddie's Beach Bar, The Little Gay Pub 같은 고정 venue 기반 bar proxy signal. direct venue source 연결 전 기본값으로 표시",
                    highlights = buildVenueHighlights(venueSnapshot.venues).take(3) + "proxy fallback",
                    source = "Tracked Venue Proxy", url = "https://www.pizzint.watch/whitepaper", experimental = true,
                    description = BAR_DESCRIPTION, methodology = BAR_METHODOLOGY,
                ),
            )
        }

        val spikingLocations = snapshot.locationSignals.count { extractSpikePercent(it.status) > 0 }
        val monitoredLocationCount = snapshot.monitoredLocationCount ?: snapshot.locationSignals.size
        val busiestLocation = snapshot.locationSignals.maxByOrNull { extractSpikePercent(it.status) }
        val maxSpike = busiestLocation?.let { extractSpikePercent(it.status) } ?: 0
        val averageSpike = snapshot.locationSignals
            .map { extractSpikePercent(it.status) }
            .filter { it > 0 }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        val doughconScore = weightedScore(
            contributions = listOf(
                normalizedContribution(snapshot.doughconLevel?.toDouble() ?: 3.0, 5.0, 0.28),
                normalizedContribution(spikingLocations.toDouble(), monitoredLocationCount.coerceAtLeast(1).toDouble(), 0.34),
                normalizedContribution(maxSpike.toDouble(), 240.0, 0.38),
            ), floor = 14,
        )
        val alertScore = weightedScore(
            contributions = listOf(
                normalizedContribution((snapshot.reportCount ?: 0).toDouble(), 60.0, 0.4),
                normalizedContribution((snapshot.alertCount ?: 0).toDouble(), 20.0, 0.35),
                normalizedContribution(averageSpike, 220.0, 0.25),
            ), floor = 8,
        )
        val quietLocationCount = snapshot.locationSignals.count {
            it.status.uppercase() in setOf("QUIET", "NOMINAL", "CLOSED")
        }
        val barCounterSignalScore = weightedScore(
            contributions = listOf(
                normalizedContribution(quietLocationCount.toDouble(), monitoredLocationCount.coerceAtLeast(1).toDouble(), 0.42),
                normalizedContribution((snapshot.alertCount ?: 0).toDouble(), 20.0, 0.33),
                normalizedContribution(maxSpike.toDouble(), 240.0, 0.25),
            ), floor = 12,
        )

        return listOf(
            AlternativeSignal(
                label = "Pentagon Pizza Index",
                score = doughconScore,
                state = buildPizzaState(doughconScore),
                note = buildPizzaNote(snapshot, busiestLocation, maxSpike, spikingLocations, monitoredLocationCount),
                highlights = listOf("DOUGHCON ${snapshot.doughconLevel ?: "-"}", "급등 ${spikingLocations}/${monitoredLocationCount}", "최고 ${maxSpike}%"),
                source = "PizzINT", url = snapshot.sourceUrl, experimental = true,
                description = PIZZA_DESCRIPTION, methodology = PIZZA_METHODOLOGY,
            ),
            AlternativeSignal(
                label = "Policy Buzz",
                score = alertScore,
                state = buildPolicyBuzzState(alertScore),
                note = "OSINT 피드 ${snapshot.reportCount ?: 0}건, 알림 ${snapshot.alertCount ?: 0}건, 평균 스파이크 ${averageSpike.toInt()}%를 합산한 정책/안보 체감 지표",
                highlights = listOf("리포트 ${snapshot.reportCount ?: 0}", "알림 ${snapshot.alertCount ?: 0}", "평균 ${averageSpike.toInt()}%"),
                source = "PizzINT OSINT Feed", url = snapshot.sourceUrl, experimental = true,
                description = POLICY_DESCRIPTION, methodology = POLICY_METHODOLOGY,
            ),
            AlternativeSignal(
                label = "Bar Counter-Signal",
                score = barCounterSignalScore,
                state = buildCounterSignalState(barCounterSignalScore),
                note = buildBarCounterSignalNote(quietLocationCount, monitoredLocationCount, maxSpike, venueSnapshot.venues),
                highlights = listOf("조용함 ${quietLocationCount}/${monitoredLocationCount}") + buildVenueHighlights(venueSnapshot.venues).take(2),
                source = "Tracked Venue Proxy", url = "https://www.pizzint.watch/whitepaper", experimental = true,
                description = BAR_DESCRIPTION, methodology = BAR_METHODOLOGY,
            ),
        )
    }

    companion object {
        private const val PIZZA_DESCRIPTION =
            "워싱턴 D.C. 펜타곤 인근 피자 매장들의 Google Maps 인기시간대(Popular Times) 데이터를 모은 공개 OSINT 지표(PizzINT). " +
            "1980년대부터 회자되는 'Pentagon Pizza' 가설 — 백악관·국방부 야근이 늘면 인근 식당이 붐빈다 — 을 데이터화한 실험 시그널."
        private const val PIZZA_METHODOLOGY =
            "1) 현재 DOUGHCON 경계 단계(0~5)\n" +
            "2) 모니터링 매장 중 평소 대비 급등(spike) 매장 비율\n" +
            "3) 가장 붐비는 매장의 % 스파이크\n" +
            "세 항목을 가중 합산 후 0~100으로 정규화."
        private const val POLICY_DESCRIPTION =
            "PizzINT가 수집하는 정책/안보 OSINT 피드(언론 보도, 공식 발표, 헬리콥터 이착륙 로그 등)의 발생 빈도와 알림 강도. " +
            "지정학·정책 이벤트가 급증할 때 위험자산 변동성이 커지는 경향을 잡기 위한 보조 지표."
        private const val POLICY_METHODOLOGY =
            "1) OSINT 리포트 건수 (스케일 60건)\n" +
            "2) 알림(alert) 건수 (스케일 20건)\n" +
            "3) 평균 매장 스파이크 %\n" +
            "가중 합산 후 0~100 정규화. 60↑이면 정책 노이즈 확대 구간."
        private const val BAR_DESCRIPTION =
            "Pentagon 인근 고정 venue(Freddie's Beach Bar, The Little Gay Pub 등)의 활동성을 역으로 본 보조 시그널. " +
            "주변 피자 매장이 모두 조용한데 술집도 평소처럼 붐빈다면 '평범한 야간', " +
            "피자 매장이 붐비는데 술집은 한산하면 '내부 업무 모드' 가설을 점검하기 위한 cross-check."
        private const val BAR_METHODOLOGY =
            "1) 모니터링 매장 중 quiet/nominal/closed 상태 비율\n" +
            "2) 정책 알림 건수\n" +
            "3) 최고 매장 스파이크 %\n" +
            "가중 합산 후 0~100 정규화. 현재는 direct venue traffic 연결 전 proxy로 운용."
    }

    private fun buildPizzaState(score: Int) = when {
        score >= 82 -> "고경계"; score >= 64 -> "주의 강화"; score >= 42 -> "보통"; score >= 22 -> "낮음"; else -> "평온"
    }

    private fun buildPizzaNote(
        snapshot: PizzIntSnapshot,
        busiestLocation: PizzIntLocationSignal?,
        maxSpike: Int,
        spikingLocations: Int,
        monitoredLocationCount: Int,
    ): String {
        val locationPart = busiestLocation?.let { "${it.name} ${it.status}" } ?: "특정 급등 매장 없음"
        return "DOUGHCON ${snapshot.doughconLevel ?: "-"}, 급등 매장 ${spikingLocations}/${monitoredLocationCount}, 최고 스파이크 ${maxSpike}%, 대표 관측치: $locationPart"
    }

    private fun buildPolicyBuzzState(score: Int) = when {
        score >= 78 -> "고강도"; score >= 58 -> "확대"; score >= 34 -> "보통"; else -> "잠잠"
    }

    private fun buildCounterSignalState(score: Int) = when {
        score >= 74 -> "야간 비정상 징후"; score >= 52 -> "내부 업무 모드 가능성"; score >= 28 -> "혼재"; else -> "특이 신호 약함"
    }

    private fun buildBarCounterSignalNote(
        quietLocationCount: Int,
        monitoredLocationCount: Int,
        maxSpike: Int,
        trackedVenues: List<VenueSignalTarget>,
    ): String {
        val venueSummary = trackedVenues.joinToString(", ") { "${it.name} ${it.distanceMiles}mi" }
        return "고정 venue(${venueSummary})를 기준으로 조용한 피자 매장 비율 ${quietLocationCount}/${monitoredLocationCount}, 최고 스파이크 ${maxSpike}%를 합산한 bar proxy signal. direct venue traffic source 확보 전까지 프록시로 운영"
    }

    private fun buildVenueHighlights(venues: List<VenueSignalTarget>): List<String> =
        venues.map { "${it.name.substringBefore(' ').trim()} ${it.distanceMiles}mi" }

    private fun normalizedContribution(value: Double, scale: Double, weight: Double): Double {
        if (scale <= 0.0) return 0.0
        return (value / scale).coerceIn(0.0, 1.0) * weight
    }

    private fun weightedScore(contributions: List<Double>, floor: Int = 0): Int {
        val total = contributions.sum().coerceIn(0.0, 1.0)
        return (floor + kotlin.math.sqrt(total) * (100 - floor)).toInt().coerceIn(0, 100)
    }

    private fun extractSpikePercent(status: String): Int =
        Regex("""(\d+)%""").find(status)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
