package com.os4.musiccover

/**
 * The pickup code on a 微信 order page, out of the page's text (PickupCodeIsland reads the text).
 *
 * The page is a mini program's XWeb WebView, and its DOM comes back as the assist structure's
 * nodes. What is handed here is those nodes' texts in tree order, each with the WebView it sits
 * in: the structure holds every page of the mini program's stack (home, order list, the order),
 * one WebView each, and the last one is the page in front.
 *
 * No two brands write the code the same way (2026-10-08, two real pages):
 *   蜜雪冰城   取餐码 / 6706 / 订单已完成，祝您用餐愉快 ... 取餐号码 / 6706 ... 门店编码 / 927639
 *   霸王茶姬   已完成 / T0295 / 取单号
 * so the label is looked for first, and the code is the nearest short code on either side of it.
 */
internal object PickupParse {

    /** One text node: [page] is the index of the WebView it is in, -1 for none. */
    data class Node(val text: String, val page: Int)

    data class Result(val code: String, val label: String, val status: String?, val store: String?)

    /** 取餐码, 取餐号, 取单号, 取茶号, 取餐号码, 取件码 ... - 取, up to two more, then 码 or 号 (码). */
    private val LABEL = Regex("^(?:您的)?取.{0,2}[码号]码?[:：]?$")
    /** The same, with the code in the same node: 「取餐号：A123」. */
    private val LABEL_AND_CODE = Regex("(取.{0,2}[码号]码?)\\s*[:：]?\\s*([A-Z]{0,2}-?\\d{2,6})$")
    private val OTHER_LABELS = setOf("排队号", "叫号", "取餐编号")
    /** A code: up to two capitals, then two to six digits. */
    private val CODE = Regex("^[A-Z]{0,2}-?\\d{2,6}$")
    /** How far from its label a code may be, in text nodes. */
    private const val REACH = 4

    /** Most specific first: 订单已完成 is 已完成. */
    val STATUSES = listOf(
        "待支付", "已下单", "已接单", "制作中", "备餐中", "出餐中", "待取餐", "请取餐", "可取餐",
        "已出餐", "配送中", "已完成", "已取消", "已退款",
    )

    fun parse(all: List<Node>): Result? {
        val nodes = all.filter { it.text.isNotBlank() }
        val front = nodes.maxOfOrNull { it.page } ?: return null
        if (front >= 0) find(nodes.filter { it.page == front })?.let { return it }
        return find(nodes)
    }

    private fun find(nodes: List<Node>): Result? {
        val t = nodes.map { it.text.trim() }
        for (i in t.indices) {
            LABEL_AND_CODE.find(t[i])?.let { m ->
                return Result(m.groupValues[2], m.groupValues[1], status(t, i), store(t, i))
            }
            if (!isLabel(t[i])) continue
            for (d in 1..REACH) {
                for (j in intArrayOf(i - d, i + d)) {
                    if (j in t.indices && isCode(t, j)) {
                        return Result(t[j], t[i].trimEnd(':', '：'), status(t, j), store(t, j))
                    }
                }
            }
        }
        return null
    }

    private fun isLabel(s: String) = s.length <= 6 && (LABEL.matches(s) || s in OTHER_LABELS)

    private fun isCode(t: List<String>, j: Int): Boolean {
        val s = t[j]
        if (!CODE.matches(s)) return false
        val before = t.getOrNull(j - 1).orEmpty()
        // A price split over two nodes: 「¥ 」 then 「8」.
        if (before.endsWith("¥") || before.endsWith("￥")) return false
        // A number the page has its own name for, 门店编码 / 927639: not ours however close.
        if (before.endsWith("编码") || before.endsWith("编号") || before.endsWith("电话")) return false
        return true
    }

    /** The order's state, the one nearest the code. */
    private fun status(t: List<String>, at: Int): String? {
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (k in t.indices) {
            val s = t[k]
            if (s.length > 14) continue
            val hit = STATUSES.firstOrNull { s.contains(it) } ?: continue
            val d = kotlin.math.abs(k - at)
            if (d < bestD) { best = hit; bestD = d }
        }
        return best
    }

    /** The shop: a short text ending in 店, 「南门店-No.927639」 read as 「南门店」. */
    private fun store(t: List<String>, at: Int): String? {
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (k in t.indices) {
            val s = t[k].substringBefore("-No.").trimEnd('>', ' ')
            if (s.length !in 2..24 || !s.endsWith("店")) continue
            if (s.contains("联系") || s.contains("致电") || s.contains("附近") || s.contains("订单")) continue
            val d = kotlin.math.abs(k - at)
            if (d < bestD) { best = s; bestD = d }
        }
        return best
    }
}
