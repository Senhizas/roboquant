/*
 * Copyright 2020-2023 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("WildcardImport", "MaxLineLength")

package org.roboquant.ibkr

import com.ib.client.*
import com.ib.client.Types.Action
import org.roboquant.brokers.*
import org.roboquant.brokers.sim.execution.InternalAccount
import org.roboquant.common.*
import org.roboquant.feeds.Event
import org.roboquant.ibkr.IBKR.toAsset
import org.roboquant.ibkr.IBKR.toContract
import org.roboquant.orders.*
import org.roboquant.orders.OrderStatus
import java.lang.Thread.sleep
import java.time.Instant
import com.ib.client.Order as IBOrder
import com.ib.client.OrderState as IBOrderSate
import com.ib.client.OrderStatus as IBOrderStatus

/**
 * Use your Interactive Brokers account for trading. Can be used with live trading or paper trading accounts of
 * Interactive Brokers. It is highly recommend to start with a paper trading account and validate your strategy and
 * policy extensively before moving to live trading.
 *
 * ## Use at your own risk, since there are no guarantees about the correct functioning of the roboquant software.
 *
 * @param configure additional configuration
 * @constructor
 */
class IBKRBroker(
    configure: IBKRConfig.() -> Unit = {}
) : Broker {

    private val config = IBKRConfig()

    private val accountId: String?
    private var client: EClientSocket
    private var _account = InternalAccount(Currency.USD)

    /**
     * @see Broker.account
     */
    override val account: Account
        get() = _account.toAccount()

    private val logger = Logging.getLogger(IBKRBroker::class)
    private var orderId = 0

    // Track IB orders ids with roboquant orders
    private val orderMap = mutableMapOf<Int, Order>()

    // Track IB Trades and Feed ids with roboquant trades
    private val tradeMap = mutableMapOf<String, Trade>()

    init {
        config.configure()
        require(config.account.isBlank() || config.account.startsWith('D')) { "only paper trading is supported" }
        accountId = config.account.ifBlank { null }
        logger.info { "using account=$accountId" }
        val wrapper = Wrapper(logger)
        client = IBKR.connect(wrapper, config)
        client.reqCurrentTime()
        client.reqAccountUpdates(true, accountId)
        // client.reqAccountSummary(9004, "All", "\$LEDGER:ALL")

        // Only request orders created with this client, so roboquant
        // doesn't use: client.reqAllOpenOrders()
        client.reqOpenOrders()
        waitTillSynced()
    }

    /**
     * Disconnect roboquant from TWS or IB Gateway
     */
    fun disconnect() = IBKR.disconnect(client)

    /**
     * Wait till IBKR account is synchronized so roboquant has the correct assets and cash balance available.
     *
     */
    private fun waitTillSynced() {
        @Suppress("MagicNumber")
        sleep(5_000)
    }


    /**
     * Cancel an order
     */
    private fun cancelOrder(cancellation: CancelOrder) {
        logger.debug("received order $cancellation")
        val id = cancellation.id
        val ibID = orderMap.filter { it.value.id == id }.keys.first()
        logger.info("cancelling order with id $ibID")
        client.cancelOrder(ibID, cancellation.tag)

        // There is no easy way to check for status of cancellation order
        // So for we set it always to completed
        val now = Instant.now()
        _account.completeOrder(cancellation, now)
    }

    /**
     * Place an order of type [SingleOrder]
     */
    private fun placeOrder(order: SingleOrder) {
        logger.info("received order=$order")
        val contract = order.asset.toContract()
        val ibOrder = createIBOrder(order)
        logger.info {
            with(ibOrder) {
                "placing order id=${orderId()} size=${totalQuantity()} type=${orderType()} contract=$contract"
            }
        }
        client.placeOrder(ibOrder.orderId(), contract, ibOrder)
    }


    /**
     * Place zero or more [orders]
     *
     * @param orders
     * @param event
     * @return
     */
    override fun place(orders: List<Order>, event: Event): Account {
        // Make sure we store all orders
        _account.initializeOrders(orders)

        for (order in orders) {
            when (order) {
                is CancelOrder -> cancelOrder(order)
                is SingleOrder -> placeOrder(order)
                else -> {
                    throw UnsupportedException("unsupported order type order=$order")
                }
            }

        }

        // Return a clone so changes to account while running policies don't cause inconsistencies.
        return _account.toAccount()
    }

    /**
     * convert a roboquant [order] to an IBKR order.
     */
    private fun createIBOrder(order: SingleOrder): IBOrder {
        val result = IBOrder()
        with(result) {
            when (order) {
                is MarketOrder -> orderType(OrderType.MKT)
                is LimitOrder -> {
                    orderType(OrderType.LMT); lmtPrice(order.limit)
                }

                is StopOrder -> {
                    orderType(OrderType.STP); auxPrice(order.stop)
                }

                is StopLimitOrder -> {
                    orderType(OrderType.STP_LMT); lmtPrice(order.limit); auxPrice(order.stop)
                }

                is TrailOrder -> {
                    orderType(OrderType.TRAIL); trailingPercent(order.trailPercentage)
                }

                else -> throw UnsupportedException("unsupported order type $order")
            }
        }

        val action = if (order.buy) Action.BUY else Action.SELL
        result.action(action)
        val qty = Decimal.get(order.size.toBigDecimal().abs())
        result.totalQuantity(qty)

        if (accountId != null) result.account(accountId)
        result.orderId(++orderId)
        orderMap[orderId] = order

        return result
    }

    /**
     * Overwrite the default wrapper
     */
    private inner class Wrapper(logger: Logging.Logger) : BaseWrapper(logger) {

        /**
         * Convert an IBOrder to a roboquant Order. This is only used during initial connect when retrieving any open
         * orders linked to the account.
         */
        private fun toOrder(order: IBOrder, contract: Contract): Order {
            val asset = contract.toAsset()
            val qty = if (order.action() == Action.BUY) order.totalQuantity() else order.totalQuantity().negate()
            val size = Size(qty.value())
            return when (order.orderType()) {
                OrderType.MKT -> MarketOrder(asset, size)
                OrderType.LMT -> LimitOrder(asset, size, order.lmtPrice())
                OrderType.STP -> StopOrder(asset, size, order.auxPrice())
                OrderType.TRAIL -> TrailOrder(asset, size, order.trailingPercent())
                OrderType.STP_LMT -> StopLimitOrder(asset, size, order.auxPrice(), order.lmtPrice())
                else -> throw UnsupportedException("$order")
            }
        }

        private fun toStatus(status: String): OrderStatus {
            return when (IBOrderStatus.valueOf(status)) {
                IBOrderStatus.Submitted -> OrderStatus.ACCEPTED
                IBOrderStatus.Cancelled -> OrderStatus.CANCELLED
                IBOrderStatus.Filled -> OrderStatus.COMPLETED
                else -> OrderStatus.INITIAL
            }
        }

        /**
         * What is the next valid IBKR orderID we can use
         */
        override fun nextValidId(id: Int) {
            orderId = id
            logger.debug("$id")
        }

        override fun openOrder(orderId: Int, contract: Contract, ibOrder: IBOrder, orderState: IBOrderSate) {
            logger.debug { "orderId=$orderId asset=${contract.symbol()} qty=${ibOrder.totalQuantity()} status=${orderState.status}" }
            logger.trace { "$orderId $contract $ibOrder $orderState" }
            val openOrder = orderMap[orderId]
            if (openOrder != null) {
                if (orderState.completedStatus() == "true") {
                    val order = _account.getOrder(openOrder.id)
                    if (order != null) {
                        _account.updateOrder(order, Instant.parse(orderState.completedTime()), OrderStatus.COMPLETED)
                    }
                }
            } else {
                val newOrder = toOrder(ibOrder, contract)
                orderMap[orderId] = newOrder
                _account.initializeOrders(listOf(newOrder))
            }
        }

        override fun orderStatus(
            orderId: Int, status: String?, filled: Decimal,
            remaining: Decimal, avgFillPrice: Double, permId: Int, parentId: Int,
            lastFillPrice: Double, clientId: Int, whyHeld: String?, mktCapPrice: Double
        ) {
            logger.debug { "orderstatus oderId=$orderId status=$status filled=$filled" }
            val order = orderMap[orderId]
            if (order != null) {
                val newStatus = toStatus(status!!)
                _account.updateOrder(order, Instant.now(), newStatus)
            } else {
                logger.warn { "Received unknown open order with orderId $orderId" }
            }
        }

        override fun accountSummary(p0: Int, p1: String?, p2: String?, p3: String?, p4: String?) {
            logger.debug { "$p0, $p1, $p2, $p3, $p4" }
        }

        override fun accountSummaryEnd(p0: Int) {
            logger.debug { "$p0" }
        }

        /**
         * This is called with fee and pnl of a trade.
         */
        override fun commissionReport(report: CommissionReport) {
            logger.debug { "commissionReport execId=${report.execId()} currency=${report.currency()} fee=${report.commission()} pnl=${report.realizedPNL()}" }
            val id = report.execId().substringBeforeLast('.')
            val trade = tradeMap[id]
            if (trade != null) {
                val i = account.trades.indexOf(trade)
                val newTrade = trade.copy(
                    feeValue = report.commission(), pnlValue = report.realizedPNL()
                )
                _account.trades[i] = newTrade
            } else {
                logger.warn("Commission for none existing trade ${report.execId()}")
            }

        }

        override fun execDetails(reqId: Int, contract: Contract, execution: Execution) {
            logger.debug { "execDetails execId: ${execution.execId()} asset: ${contract.symbol()} side: ${execution.side()} qty: ${execution.cumQty()} price: ${execution.avgPrice()}" }

            // The last number is to correct an existing execution, so not a new execution
            val id = execution.execId().substringBeforeLast('.')

            if (id in tradeMap) {
                logger.info("trade already handled, no support for corrections currently")
                return
            }

            // Possible values BOT and SLD
            val size = if (execution.side() == "SLD") -execution.cumQty().value() else execution.cumQty().value()
            val order = orderMap[execution.orderId()]

            if (order != null) {
                val trade = Trade(
                    Instant.now(),
                    contract.toAsset(),
                    Size(size),
                    execution.avgPrice(),
                    Double.NaN,
                    Double.NaN,
                    order.id
                )
                tradeMap[id] = trade
                _account.addTrade(trade)
            }
        }

        override fun openOrderEnd() {
            logger.debug("openOrderEnd")
        }

        override fun accountDownloadEnd(p0: String?) {
            logger.debug("accountDownloadEnd $p0")
        }

        override fun updateAccountValue(key: String, value: String, currency: String?, accountName: String?) {
            logger.debug { "updateAccountValue key=$key value=$value currency=$currency account=$accountName" }

            if (key == "AccountCode") require(value.startsWith('D')) {
                "currently only paper trading account is supported, found $value"
            }

            if (currency != null && "BASE" != currency) {
                when (key) {
                    "BuyingPower" -> {
                        _account.baseCurrency = Currency.getInstance(currency)
                        _account.buyingPower = Amount(_account.baseCurrency, value.toDouble())
                    }

                    "CashBalance" -> _account.cash.set(Currency.getInstance(currency), value.toDouble())
                }
            }
        }

        override fun updatePortfolio(
            contract: Contract,
            position: Decimal,
            marketPrice: Double,
            marketValue: Double,
            averageCost: Double,
            unrealizedPNL: Double,
            realizedPNL: Double,
            accountName: String
        ) {
            logger.debug { "updatePortfolio asset: ${contract.symbol()} position: $position price: $marketPrice cost: $averageCost" }
            logger.trace { "updatePortfolio $contract $position $marketPrice $averageCost" }
            val asset = contract.toAsset()
            val size = Size(position.value())
            val p = Position(asset, size, averageCost, marketPrice, Instant.now())
            _account.setPosition(p)
        }

        override fun updateAccountTime(timeStamp: String) {
            logger.debug(timeStamp)
            _account.lastUpdate = Instant.now()
        }


    }
}

