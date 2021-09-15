package org.roboquant

import org.roboquant.brokers.alpaca.AlpacaBroker
import org.roboquant.common.Asset
import org.roboquant.common.Config
import org.roboquant.common.TimeFrame
import org.roboquant.feeds.alpaca.AlpacaFeed
import org.roboquant.feeds.csv.CSVConfig
import org.roboquant.feeds.csv.CSVFeed
import org.roboquant.feeds.yahoo.YahooFinanceFeed
import org.roboquant.logging.MemoryLogger
import org.roboquant.metrics.AccountSummary
import org.roboquant.metrics.OpenPositions
import org.roboquant.metrics.ProgressMetric
import org.roboquant.strategies.EMACrossover


fun alpacaBroker() {
    val feed = CSVFeed("data/US", CSVConfig(priceAdjust = true))
    val broker = AlpacaBroker()
    val strategy = EMACrossover.midTerm()
    val roboquant = Roboquant(strategy, AccountSummary(), broker = broker)
    roboquant.run(feed)
    broker.account.summary().print()
}


fun allAlpaca() {
    val feed = AlpacaFeed()
    feed.subscribe()

    feed.timeMillis = 1000
    val strategy = EMACrossover.shortTerm()
    val broker = AlpacaBroker()
    broker.account.summary().print()
    val roboquant = Roboquant(strategy, AccountSummary(), ProgressMetric(), broker = broker)
    val tf = TimeFrame.nextMinutes(10)
    roboquant.run(feed, tf)
    roboquant.broker.account.summary().print()
    roboquant.logger.summary(3).print()
    feed.disconnect()
}


fun alpacaFeed() {
    val feed = AlpacaFeed()
    val assets = feed.assets.take(50)
    feed.subscribe(assets)
    feed.timeMillis = 1000
    val strategy = EMACrossover.midTerm()
    val roboquant = Roboquant(strategy, AccountSummary(), ProgressMetric())
    val tf = TimeFrame.nextMinutes(5)
    roboquant.run(feed, tf)
    feed.disconnect()

    roboquant.broker.account.summary().log()
    roboquant.logger.summary().log()

}

fun feedIEX() {
    val token = Config.getProperty("IEX_PUBLISHABLE_TOKEN") ?: throw Exception("No token found")
    val feed = org.roboquant.feeds.iex.IEXFeed(token)
    feed.retrievePriceBar("AAPL", "GOOGL", "FB", range = "2y")

    val strategy = EMACrossover(10, 30)
    val roboquant = Roboquant(strategy, AccountSummary())
    roboquant.run(feed)
    roboquant.broker.account.summary().log()
}


fun feedIEXLive() {
    val token = System.getenv("IEX_PUBLISHABLE_TOKEN") ?: throw Exception("No token found")
    val feed = org.roboquant.feeds.iex.IEXLive(token)
    val apple = Asset("AAPL")
    val google = Asset("GOOG")
    feed.subscribeTrades(apple, google)

    val strategy = EMACrossover()
    val roboquant = Roboquant(strategy, AccountSummary(), ProgressMetric())
    roboquant.run(feed, TimeFrame.nextMinutes(2))
    roboquant.broker. account.summary().log()
    roboquant.logger.summary().log()
}


fun feedYahoo() {

    val feed = YahooFinanceFeed()
    val apple = Asset("AAPL")
    val google = Asset("GOOG")
    val last300Days = TimeFrame.pastPeriod(300)
    feed.retrieve(apple, google, timeFrame = last300Days)

    val strategy = EMACrossover()
    val logger = MemoryLogger()
    val roboquant = Roboquant(strategy, AccountSummary(), OpenPositions(), logger = logger)
    roboquant.run(feed)
    logger.summary(10)
}

fun main() {
    when ("ALPACA_FEED") {
        "IEX" -> feedIEX()
        "IEX_LIVE" -> feedIEXLive()
        "YAHOO" -> feedYahoo()
        "ALPACA_BROKER" -> alpacaBroker()
        "ALPACA_FEED" -> alpacaFeed()
        "ALPACA_ALL" -> allAlpaca()
    }
}