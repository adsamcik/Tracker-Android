package com.adsamcik.signals.network


class Prices {
    var PRICE_30DAY_MAP: Int = 0
    var PRICE_30DAY_PERSONAL_MAP: Int = 0

    companion object {
        fun mock(): Prices {
            val prices = Prices()
            prices.PRICE_30DAY_PERSONAL_MAP = 7500
            prices.PRICE_30DAY_MAP = 2000
            return prices
        }
    }
}
