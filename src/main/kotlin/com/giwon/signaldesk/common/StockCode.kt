package com.giwon.signaldesk.common

/** KR 상장 종목코드(6자리 숫자) 여부. 비상장/펀드/해외티커는 false. */
fun String.isKrStockCode(): Boolean = length == 6 && all(Char::isDigit)
