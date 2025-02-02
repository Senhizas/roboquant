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
package org.roboquant.brokers.sim

import org.roboquant.brokers.sim.execution.Execution
import kotlin.math.absoluteValue

/**
 * Default fee model, using a fixed percentage fee of total absolute value of the execution.
 *
 * @property feePercentage fee as a percentage of total execution cost, 0.01 = 1%. Default is 0.0
 * @constructor Create a new percentage fee model
 */
class PercentageFeeModel(
    private val feePercentage: Double = 0.0,
) : FeeModel {

    override fun calculate(execution: Execution): Double {
        return execution.value.absoluteValue * feePercentage
    }

}