package com.majsoul.tiles

/**
 * 纯 Kotlin 日本麻将牌效分析器
 * 移植自 Python analyzer.py
 *
 * 牌面编码: 0-8=万, 9-17=筒, 18-26=索, 27-33=字(东南西北白发中)
 */
object ShantenAnalyzer {

    private val _1_9 = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
    val honors = listOf("東", "南", "西", "北", "白", "發", "中")

    private val TILE_MAP: Map<String, Int> = buildMap {
        for (i in 1..9) {
            put("${_1_9[i - 1]}万", i - 1)
            put("${_1_9[i - 1]}筒", 9 + i - 1)
            put("${_1_9[i - 1]}索", 18 + i - 1)
        }
        honors.forEachIndexed { i, name -> put(name, 27 + i) }
    }

    fun tileToInt(name: String): Int = TILE_MAP[name] ?: -1

    fun intToTile(n: Int): String = when {
        n < 9 -> "${n + 1}万"
        n < 18 -> "${n - 8}筒"
        n < 27 -> "${n - 17}索"
        else -> honors[n - 27]
    }

    data class Candidate(
        val tile: Int,
        val shantenAfter: Int,
        val ukeireTypes: Int,
        val ukeireCount: Int,
        val ukeireTiles: List<Int>
    )

    data class ShantenResult(
        val shanten: Int,
        val mentsu: Int,
        val tatsu: Int,
        val hasPair: Boolean
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "mentsu" to mentsu,
            "tatsu" to tatsu,
            "pair" to hasPair
        )
    }

    // ===== 向听数计算 =====

    fun calcShanten(hand: List<Int>): ShantenResult {
        val countsFixed = IntArray(34)
        hand.forEach { countsFixed[it]++ }

        var bestShanten = 99
        var bestMentsu = 0
        var bestTatsu = 0
        var bestPair = false

        // 找所有可能的雀头候选
        val pairCands = mutableListOf<Int?>()
        for (i in 0..33) {
            if (countsFixed[i] >= 2) pairCands.add(i)
        }
        pairCands.add(null) // 无雀头

        for (pair in pairCands) {
            val remain = countsFixed.copyOf()
            if (pair != null) {
                remain[pair] -= 2
                if (remain[pair] < 0) continue
            }

            val mentsu = countMentsu(remain.copyOf())
            val afterMentsu = applyMentsu(remain.copyOf())
            val tatsu = countTatsu(afterMentsu)

            val maxTatsu = minOf(tatsu, 4 - mentsu)
            var shanten = 4 - mentsu - maxTatsu
            if (pair == null) shanten += 1

            if (shanten < bestShanten) {
                bestShanten = shanten
                bestMentsu = mentsu
                bestTatsu = maxTatsu
                bestPair = pair != null
            }
        }

        return ShantenResult(bestShanten, bestMentsu, bestTatsu, bestPair)
    }

    private fun countMentsu(counts: IntArray): Int {
        var mentsu = 0
        val r = counts.copyOf()

        // 先找刻子
        for (t in 0..33) {
            while (r[t] >= 3) {
                r[t] -= 3
                mentsu++
            }
        }

        // 再找顺子（只对万/筒/索）
        for (offset in listOf(0, 9, 18)) {
            for (start in offset until offset + 7) {
                while (r[start] >= 1 && r[start + 1] >= 1 && r[start + 2] >= 1) {
                    r[start]--
                    r[start + 1]--
                    r[start + 2]--
                    mentsu++
                }
            }
        }

        return mentsu
    }

    private fun applyMentsu(counts: IntArray): IntArray {
        val r = counts.copyOf()
        for (t in 0..33) {
            while (r[t] >= 3) r[t] -= 3
        }
        for (offset in listOf(0, 9, 18)) {
            for (start in offset until offset + 7) {
                while (r[start] >= 1 && r[start + 1] >= 1 && r[start + 2] >= 1) {
                    r[start]--
                    r[start + 1]--
                    r[start + 2]--
                }
            }
        }
        return r
    }

    private fun countTatsu(counts: IntArray): Int {
        val r = counts.copyOf()
        var tatsu = 0

        // 对子
        for (t in 0..33) {
            if (r[t] >= 2) {
                r[t] -= 2
                tatsu++
            }
        }

        // 搭子（万/筒/索）
        for (offset in listOf(0, 9, 18)) {
            val inSuit = (offset until offset + 9).filter { r[it] > 0 }.toMutableList()
            var changed = true
            while (changed) {
                changed = false
                for (i in 0 until inSuit.size - 1) {
                    val a = inSuit[i]
                    val b = inSuit[i + 1]
                    if (b - a <= 2) {
                        r[a]--
                        r[b]--
                        tatsu++
                        changed = true
                        inSuit.clear()
                        inSuit.addAll((offset until offset + 9).filter { r[it] > 0 })
                        break
                    }
                }
            }
        }
        return tatsu
    }

    // ===== 进张数计算 =====

    fun calcUkeire(hand: List<Int>, discard: Int): Triple<Int, Int, List<Int>> {
        val remaining = hand.toMutableList()
        remaining.remove(discard)
        if (remaining.isEmpty()) return Triple(0, 0, emptyList())

        val shantenAfter = calcShanten(remaining).shanten
        val ukeireTiles = mutableListOf<Int>()
        var ukeireCount = 0

        for (tile in 0..33) {
            val testHand = remaining + tile
            val testShanten = calcShanten(testHand).shanten
            if (testShanten < shantenAfter) {
                val used = hand.count { it == tile }
                val available = 4 - used
                if (available > 0) {
                    ukeireTiles.add(tile)
                    ukeireCount += available
                }
            }
        }

        return Triple(ukeireTiles.size, ukeireCount, ukeireTiles)
    }

    fun analyzeHand(hand: List<Int>): List<Candidate> {
        val analyzed = mutableSetOf<Int>()
        val results = mutableListOf<Candidate>()

        for (i in hand.indices) {
            val tile = hand[i]
            if (tile in analyzed) continue
            analyzed.add(tile)

            val remaining = hand.filterIndexed { j, _ -> j != i }
            val newShanten = calcShanten(remaining).shanten
            val (types, count, tiles) = calcUkeire(hand, tile)

            results.add(Candidate(tile, newShanten, types, count, tiles))
        }

        results.sortWith(compareBy<Candidate> { it.shantenAfter }.thenByDescending { it.ukeireCount }.thenByDescending { it.ukeireTypes })
        return results
    }

    fun isAgari(hand: List<Int>): Boolean {
        if (hand.size % 3 != 2) return false
        return calcShanten(hand).shanten <= -1
    }
}
