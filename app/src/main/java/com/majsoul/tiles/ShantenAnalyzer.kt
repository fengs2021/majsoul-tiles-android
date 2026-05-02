package com.majsoul.tiles

/**
 * 日本麻将牌效分析器
 * 基于 Anistorica1/Majiang RiichiAI 优化
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
        n < 9 -> "${_1_9[n]}万"
        n < 18 -> "${_1_9[n - 9]}筒"
        n < 27 -> "${_1_9[n - 18]}索"
        else -> honors[n - 27]
    }

    data class Candidate(
        val tile: Int,
        val shantenAfter: Int,
        val ukeireTypes: Int,
        val ukeireCount: Int,
        val ukeireTiles: List<Int>,
        val waitQuality: Int = 0,
        val isBest: Boolean = false
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
        val counts = IntArray(34)
        hand.forEach { counts[it]++ }

        var bestShanten = 99
        var bestMentsu = 0
        var bestTatsu = 0
        var bestPair = false

        val pairCands = mutableListOf<Int?>()
        for (i in 0..33) {
            if (counts[i] >= 2) pairCands.add(i)
        }
        pairCands.add(null)

        for (pair in pairCands) {
            val remain = counts.copyOf()
            if (pair != null) {
                remain[pair] -= 2
                if (remain[pair] < 0) continue
            }

            val (mentsu, tatsu) = countMentsuAndTatsu(remain)
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

    /**
     * 同时数面子数和搭子数（避免重复计数）
     */
    private fun countMentsuAndTatsu(counts: IntArray): Pair<Int, Int> {
        val r = counts.copyOf()
        var mentsu = 0

        // 先拆刻子
        for (t in 0..33) {
            while (r[t] >= 3) { r[t] -= 3; mentsu++ }
        }

        // 再拆顺子（万/筒/索）
        for (offset in listOf(0, 9, 18)) {
            for (start in offset until offset + 7) {
                while (r[start] >= 1 && r[start + 1] >= 1 && r[start + 2] >= 1) {
                    r[start]--; r[start + 1]--; r[start + 2]--
                    mentsu++
                }
            }
        }

        // 数搭子
        var tatsu = 0
        // 对子
        for (t in 0..33) {
            if (r[t] >= 2) { r[t] -= 2; tatsu++ }
        }
        // 搭子（万/筒/索）：两面对子、嵌张、边张
        for (offset in listOf(0, 9, 18)) {
            var changed = true
            while (changed) {
                changed = false
                for (start in offset until offset + 8) {
                    if (r[start] > 0 && r[start + 1] > 0) {
                        r[start]--; r[start + 1]--; tatsu++; changed = true
                        break
                    }
                }
                if (changed) continue
                for (start in offset until offset + 7) {
                    if (r[start] > 0 && r[start + 2] > 0) {
                        r[start]--; r[start + 2]--; tatsu++; changed = true
                        break
                    }
                }
            }
        }

        return Pair(mentsu, tatsu)
    }

    // ===== 进张数计算 =====

    fun calcUkeire(hand: List<Int>, discardTile: Int): Triple<Int, Int, List<Int>> {
        val remaining = hand.toMutableList()
        remaining.remove(discardTile)
        if (remaining.isEmpty()) return Triple(0, 0, emptyList())

        val shantenAfter = calcShanten(remaining).shanten
        val ukeireTiles = mutableListOf<Int>()
        var ukeireCount = 0

        for (tile in 0..33) {
            val used = hand.count { it == tile }
            if (used >= 4) continue
            val testHand = remaining + tile
            if (calcShanten(testHand).shanten < shantenAfter) {
                ukeireTiles.add(tile)
                ukeireCount += (4 - used)
            }
        }

        return Triple(ukeireTiles.size, ukeireCount, ukeireTiles)
    }

    // ===== 听牌质量（移植自 RiichiAI._wait_quality） =====

    /**
     * 评估手牌搭子质量
     * - 两面搭子（中张）: +3
     * - 边张搭子（1/9）: -1
     */
    fun calcWaitQuality(counts: IntArray): Int {
        var score = 0
        for (offset in listOf(0, 9, 18)) {
            for (start in offset until offset + 7) {
                if (counts[start] > 0 && counts[start + 1] > 0 && counts[start + 2] > 0) {
                    score += 3  // 两面/三面
                }
            }
            // 边张惩罚
            if (counts[offset] > 0 && counts[offset + 1] > 0) score -= 1
            if (counts[offset + 7] > 0 && counts[offset + 8] > 0) score -= 1
        }
        return score
    }

    // ===== 断幺九检测 =====

    /**
     * 断幺九倾向: 幺九牌扣分，中张牌加分
     */
    fun tanyaoScore(counts: IntArray): Int {
        var score = 0
        for (i in 0..33) {
            if (counts[i] > 0) {
                val isYaochu = i >= 27 || i % 9 == 0 || i % 9 == 8
                score += if (isYaochu) -2 else 1
            }
        }
        return score
    }

    // ===== 手牌分析（增强版） =====

    fun analyzeHand(hand: List<Int>): List<Candidate> {
        val counts = IntArray(34)
        hand.forEach { counts[it]++ }

        val analyzed = mutableSetOf<Int>()
        val results = mutableListOf<Candidate>()

        for (i in hand.indices) {
            val tile = hand[i]
            if (tile in analyzed) continue
            analyzed.add(tile)

            val remaining = hand.filterIndexed { j, _ -> j != i }
            val remainCounts = counts.copyOf()
            remainCounts[tile]--

            val newShanten = calcShanten(remaining).shanten
            val (types, count, tiles) = calcUkeire(hand, tile)
            val wq = calcWaitQuality(remainCounts)

            results.add(Candidate(tile, newShanten, types, count, tiles, wq))
        }

        // 排序: 向听数优先 > 进张数 > 听牌质量 > 进张种类
        results.sortWith(compareBy<Candidate> { it.shantenAfter }
            .thenByDescending { it.ukeireCount }
            .thenByDescending { it.waitQuality }
            .thenByDescending { it.ukeireTypes })

        // 标记最佳
        if (results.isNotEmpty()) {
            results[0] = results[0].copy(isBest = true)
        }

        return results
    }

    fun isAgari(hand: List<Int>): Boolean {
        if (hand.size % 3 != 2) return false
        return calcShanten(hand).shanten <= -1
    }
}
