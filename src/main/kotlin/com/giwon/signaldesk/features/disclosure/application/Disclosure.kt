package com.giwon.signaldesk.features.disclosure.application

/**
 * OpenDART 공시 1건. rceptNo 가 PK.
 *  - stockCode: 6자리 종목코드, 비상장사면 빈 문자열
 *  - rceptDt: YYYYMMDD
 *  - reportNm: 공시 제목 (e.g. "주요사항보고서(자기주식취득결정)")
 */
data class Disclosure(
    val rceptNo: String,
    val corpCode: String,
    val corpName: String,
    val stockCode: String,
    val reportNm: String,
    val rceptDt: String,
    val flrNm: String,
)

/** corpCode.xml 1행. listed company 만 (stockCode 6자리). */
data class CorpCodeEntry(
    val corpCode: String,
    val corpName: String,
    val stockCode: String,
)
