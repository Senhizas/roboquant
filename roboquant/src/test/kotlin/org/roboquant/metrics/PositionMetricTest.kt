/*
 * Copyright 2020-2022 Neural Layer
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

package org.roboquant.metrics

import org.roboquant.TestData
import org.roboquant.brokers.assets
import org.roboquant.feeds.Event
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PositionMetricTest {

    @Test
    fun calc() {
        val metric = PositionMetric()
        val account = TestData.usAccount()
        val result = metric.calculate(account, Event.empty())
        assertFalse(result.isEmpty())
        val symbol = account.positions.assets.first().symbol
        assertContains(result, "position.$symbol.size")
        assertTrue(result.all { it.key.startsWith("position.") })
    }
}