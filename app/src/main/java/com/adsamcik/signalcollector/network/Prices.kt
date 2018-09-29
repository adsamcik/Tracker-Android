package com.adsamcik.signalcollector.network


class Prices {
    var price30DayMap: Int = 0
    var price30DayPersonalMap: Int = 0

    companion object {
        fun mock(): Prices {
            val prices = Prices()
            prices.price30DayPersonalMap = 7500
            prices.price30DayMap = 2000
            return prices
        }
    }
}
