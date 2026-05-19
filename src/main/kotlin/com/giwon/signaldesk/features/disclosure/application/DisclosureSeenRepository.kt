package com.giwon.signaldesk.features.disclosure.application

interface DisclosureSeenRepository {
    /** rceptNo 기준 dedup. */
    fun filterUnseen(rceptNos: Collection<String>): Set<String>
    fun markSeen(disclosures: List<Disclosure>)
    /** 보유/관심 종목의 최근 공시 — 사용자별 조회용. */
    fun findRecentByStockCodes(stockCodes: Collection<String>, limit: Int): List<Disclosure>
}
