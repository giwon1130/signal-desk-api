package com.giwon.signaldesk.features.fortune.application

import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/**
 * 사용자+날짜 시드로 오늘의 운세를 결정론적으로 생성.
 *
 * 원칙:
 * - 같은 사용자가 같은 날 여러 번 호출해도 결과가 같아야 한다 (시드 고정).
 * - 비로그인(userId == null)도 호출 가능. 이때는 "익명 방문자"용 공통 운세.
 * - 점수는 너무 극단적으로 낮게 깔리지 않게 25~95 범위로 살짝 긍정 편향.
 * - 문구는 주식 도메인 맥락을 끼얹되, 매매 근거로 쓰지 말라고 면책 포함.
 */
@Service
class FortuneService {

    private val zone = ZoneId.of("Asia/Seoul")

    fun today(userId: UUID?): DailyFortune {
        val date = LocalDate.now(zone)
        val seed = buildSeed(userId, date)
        val rng = Random(seed)

        // 점수는 약간의 양의 편향 (25~95)
        val overall = rng.nextInt(25, 96)
        val wealth = clampScore(overall + rng.nextInt(-15, 16))
        val trade = clampScore(overall + rng.nextInt(-15, 16))
        val patience = clampScore(overall + rng.nextInt(-10, 20))

        val label = when {
            overall >= 85 -> "대길"
            overall >= 70 -> "길"
            overall >= 50 -> "평"
            overall >= 35 -> "주의"
            else          -> "흉"
        }
        val tone = when {
            overall >= 70 -> "good"
            overall >= 50 -> "neutral"
            else          -> "bad"
        }

        val headlineBank = when (tone) {
            "good"    -> GOOD_HEADLINES
            "neutral" -> NEUTRAL_HEADLINES
            else      -> BAD_HEADLINES
        }
        val messageBank = when (tone) {
            "good"    -> GOOD_MESSAGES
            "neutral" -> NEUTRAL_MESSAGES
            else      -> BAD_MESSAGES
        }
        val mantraBank = when (tone) {
            "good"    -> GOOD_MANTRAS
            "neutral" -> NEUTRAL_MANTRAS
            else      -> BAD_MANTRAS
        }

        val luckyHourStart = rng.nextInt(9, 21) // 9시~20시 시작
        val luckyHour = "${luckyHourStart}시 ~ ${luckyHourStart + 1}시"
        val luckyColor = LUCKY_COLORS[rng.nextInt(LUCKY_COLORS.size)]
        val luckyNumber = rng.nextInt(1, 10)
        val luckyTheme = when (tone) {
            "good"    -> GOOD_THEMES[rng.nextInt(GOOD_THEMES.size)]
            "neutral" -> NEUTRAL_THEMES[rng.nextInt(NEUTRAL_THEMES.size)]
            else      -> BAD_THEMES[rng.nextInt(BAD_THEMES.size)]
        }
        val caution = CAUTIONS[rng.nextInt(CAUTIONS.size)]

        return DailyFortune(
            date = date.toString(),
            overallScore = overall,
            overallLabel = label,
            overallTone = tone,
            headline = headlineBank[rng.nextInt(headlineBank.size)],
            message = messageBank[rng.nextInt(messageBank.size)],
            wealthScore = wealth,
            tradeScore = trade,
            patienceScore = patience,
            luckyHour = luckyHour,
            luckyColor = luckyColor,
            luckyNumber = luckyNumber,
            luckyTheme = luckyTheme,
            caution = caution,
            mantra = mantraBank[rng.nextInt(mantraBank.size)],
            disclaimer = "재미용. 실제 매매 판단은 본인 책임이야.",
        )
    }

    private fun buildSeed(userId: UUID?, date: LocalDate): Long {
        val userPart = userId?.let { it.mostSignificantBits xor it.leastSignificantBits } ?: 0L
        return date.toEpochDay() * 2654435761L xor userPart
    }

    private fun clampScore(value: Int): Int = value.coerceIn(10, 99)

    companion object {
        private val GOOD_HEADLINES = listOf(
            "바람이 네 쪽으로 부는 날",
            "손이 맞는 날, 판단이 선명해",
            "시장이 네 결을 읽어주는 하루",
            "조용히 먹는 수익이 쌓이는 날",
            "기다린 값을 받아갈 수 있는 하루",
        )
        private val NEUTRAL_HEADLINES = listOf(
            "서두르지 말고 관찰부터",
            "큰 베팅보단 잔잔한 관리",
            "흐름 파악에 시간 써도 되는 날",
            "감정보다 체크리스트가 이기는 날",
            "중립 포지션이 제일 단단한 하루",
        )
        private val BAD_HEADLINES = listOf(
            "무리하면 본전도 못 찾는 날",
            "손이 꼬이기 쉬운 하루",
            "욕심이 제일 비싼 날",
            "한 걸음 물러나야 보이는 날",
            "거래량보다 커피 한 잔이 나은 하루",
        )

        private val GOOD_MESSAGES = listOf(
            "계획한 자리만 지키면 수익이 자연스럽게 따라온다. 한 번 이기고 끝내는 절제가 오늘의 운을 배가시킨다.",
            "시야가 넓어지는 하루. 섹터 로테이션의 초입에서 먼저 움직이는 쪽이 유리하다.",
            "눈여겨보던 종목이 손에 잡히는 자리로 내려올 수 있다. 분할 매수로 받아내라.",
            "작은 확신이 큰 확신보다 안전한 날. 사이즈보다 타점이 중요하다.",
        )
        private val NEUTRAL_MESSAGES = listOf(
            "오늘은 베팅의 날이 아니라 체크의 날. 손실 한도와 익절 라인을 먼저 점검하자.",
            "뉴스보다 수급을 봐라. 흔들리는 호가창에서 감정이 끼어들면 바로 손이 나간다.",
            "관망이 손해 같지만 사실은 가장 저렴한 선택이다. 돈을 안 쓰는 것도 실력.",
            "기대수익과 리스크가 비슷한 날. 한쪽으로 기울지 말고 균형을 유지해.",
        )
        private val BAD_MESSAGES = listOf(
            "오늘 잃은 돈은 내일 벌 수 있지만 오늘 부서진 심리는 한 주를 망친다. 일찍 닫아도 괜찮다.",
            "추격 매수는 특히 조심. 이미 달린 차트에 올라타면 끝자락에서 내리는 사람이 너다.",
            "손절 라인 무시하고 물타기 시작하는 순간 끝이다. 규칙을 먼저 믿어.",
            "확신처럼 보이는 신호가 실은 과열일 수 있다. 의심 한 스푼 더 넣고 움직여.",
        )

        private val GOOD_MANTRAS = listOf(
            "계획대로. 욕심 대신 루틴.",
            "이긴 자리에서 바로 내려오기.",
            "짧게 먹고 길게 웃기.",
            "내 타점만, 내 사이즈만.",
        )
        private val NEUTRAL_MANTRAS = listOf(
            "안 사는 것도 선택이다.",
            "호가창 말고 체크리스트.",
            "현금도 포지션이다.",
            "반 박자 늦게, 두 배로 확실하게.",
        )
        private val BAD_MANTRAS = listOf(
            "오늘은 지지 않는 게 이기는 날.",
            "손절은 수수료, 물타기는 청구서.",
            "시장은 내일도 열린다.",
            "쉬는 것도 트레이딩.",
        )

        private val LUCKY_COLORS = listOf(
            "잔잔한 네이비",
            "차분한 그레이",
            "수수한 아이보리",
            "단단한 올리브",
            "맑은 스카이블루",
            "깊은 버건디",
            "부드러운 샌드베이지",
            "맑은 민트",
        )

        private val GOOD_THEMES = listOf(
            "반도체 대형주",
            "AI 인프라",
            "방산",
            "조선",
            "금융주",
            "바이오 성장주",
            "고배당 ETF",
            "원전/전력",
        )
        private val NEUTRAL_THEMES = listOf(
            "지수 ETF 코어 보유",
            "현금 50% 유지",
            "저변동 우량주",
            "관심종목 모니터링",
            "분산된 배당주",
        )
        private val BAD_THEMES = listOf(
            "현금 비중 확대",
            "달러 자산 비중 체크",
            "단기채 / MMF",
            "관망 (신규 진입 자제)",
            "손절 라인 재정비",
        )

        private val CAUTIONS = listOf(
            "추격 매수는 오늘 특히 금물.",
            "손절 라인 건드리지 말 것.",
            "레버리지/미수 거래는 오늘 쉬어가자.",
            "풍문·단톡방 정보로 손 나가는 거 조심.",
            "장 시작 30분 이내 충동 진입 주의.",
            "본전 심리에 물타기 시작하지 말 것.",
            "종가 직전 뇌동매매 자제.",
        )
    }
}
