/*
 * FelicaCardTransitFactory.kt
 *
 * Copyright 2018-2019 Google
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package au.id.micolous.metrodroid.card.felica

import au.id.micolous.metrodroid.multi.JvmDefault
import au.id.micolous.metrodroid.transit.CardInfo
import au.id.micolous.metrodroid.transit.CardTransitFactory

interface FelicaCardTransitFactory : CardTransitFactory<FelicaCard> {
    @JvmDefault
    override fun check(card: FelicaCard) = earlyCheck(card.systems.keys.toList())

    fun earlyCheck(systemCodes: List<Int>): Boolean

    @JvmDefault
    fun getCardInfo(systemCodes: List<Int>): CardInfo? = allCards[0]
}
