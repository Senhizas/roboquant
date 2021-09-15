package org.roboquant.metrics

import org.roboquant.Phase
import org.roboquant.brokers.*
import org.roboquant.common.TimeFrame
import org.roboquant.common.plus
import org.roboquant.feeds.Event
import java.time.Instant

/**
 * Metric that calculates both the realized and unrealized Profit and Loss. The unrealized PNL is calculated based
 * on the assets in the portfolio and the last known market price. The realized PNL is based on actual trades made
 * and the profit they generated.
 *
 * @property cumulative Should the realized PNL be aggregated or not
 * @constructor Create new PNL metric
 */
class PNL(
    private val cumulative: Boolean = false,
) : SimpleMetric() {

    private var lastTime = Instant.MIN

    /**
     * Calculate any metrics given the event of information. This will be called at the
     * end of each step in a run. The result is returned using the base currency of
     * the account.
     *
     * @param account
     * @return
     */
    override fun calc(account: Account, event: Event): MetricResults {
        val now = event.now
        val result = mutableMapOf<String, Double>()

        val pnl = if (cumulative) {
            // If cumulative just add all the trades together
            account.trades.realizedPnL()
        } else {
            // If not cumulative. only look at last timeframe
            val tf = TimeFrame(lastTime, now + 1)
            lastTime = now + 1
            account.trades.realizedPnL(tf)
        }

        val realizedPNL = account.convertToCurrency(pnl, now = now)
        result["pnl.realized"] = realizedPNL

        val totalValue = account.portfolio.unrealizedPNL()
        val unrealizedPNL = account.convertToCurrency(totalValue, now = now)
        result["pnl.unrealized"] = unrealizedPNL

        return result
    }


    override fun start(phase: Phase) {
        lastTime = Instant.MIN
    }

}