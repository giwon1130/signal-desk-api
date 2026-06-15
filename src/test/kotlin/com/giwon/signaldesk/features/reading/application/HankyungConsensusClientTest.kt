package com.giwon.signaldesk.features.reading.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 한경 컨센서스 행 파싱 회귀 보호 — 실제 테이블 구조(td 순서) 기반 샘플.
 */
class HankyungConsensusClientTest {

    private val sample = """
        <table><tbody>
        <tr class="first">
          <td class="first txt_number">2026-06-15</td>
          <td class="text_l"><a href="/analysis/downpdf?report_idx=650060" target="_blank">SK이노베이션(096770) 업데이트 노트 </a>
            <div class="layerPop"><strong>SK이노베이션(096770) 업데이트 노트 </strong></div></td>
          <td class="text_r txt_number">102,000</td>
          <td> Hold </td>
          <td>정경희</td>
          <td>LS증권</td>
          <td><div class="dv_input"></div></td>
        </tr>
        <tr>
          <td class="first txt_number">2026-06-15</td>
          <td class="text_l"><a href="/analysis/downpdf?report_idx=650061" target="_blank">고려아연(010130) 자원민족주의의시대</a></td>
          <td class="text_r txt_number">1,700,000</td>
          <td> Buy </td>
          <td>김윤상</td>
          <td>iM증권</td>
          <td><div class="dv_input"></div></td>
        </tr>
        </tbody></table>
    """.trimIndent()

    private val client = HankyungConsensusClient()

    @Test
    fun `행에서 종목코드·목표가·투자의견·증권사를 파싱한다`() {
        val rows = client.parse(sample)
        assertThat(rows).hasSize(2)

        val sk = rows[0]
        assertThat(sk.reportIdx).isEqualTo("650060")
        assertThat(sk.ticker).isEqualTo("096770")
        assertThat(sk.name).contains("SK이노베이션")
        assertThat(sk.targetPrice).isEqualTo(102000)
        assertThat(sk.opinion).isEqualTo("Hold")
        assertThat(sk.firm).isEqualTo("LS증권")

        val koZinc = rows[1]
        assertThat(koZinc.ticker).isEqualTo("010130")
        assertThat(koZinc.targetPrice).isEqualTo(1_700_000)
        assertThat(koZinc.opinion).isEqualTo("Buy")
        assertThat(koZinc.firm).isEqualTo("iM증권")
    }
}
