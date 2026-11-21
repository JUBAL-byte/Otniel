package app.jubal.otniel

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** A piece of a lesson, answer or page, shown natively (no web view anywhere). */
sealed interface Block {
    /** [html] holds only inline markup (bold, links...). */
    data class Text(val html: String, val style: Int = BODY) : Block
    data class Image(val url: String, val caption: String = "") : Block
    data class YouTube(val id: String, val startSec: Long = 0) : Block
    data class Video(val url: String) : Block
    data class Audio(val url: String) : Block
    data class File(val url: String, val name: String) : Block

    companion object {
        const val BODY = 0
        const val HEADING = 1
        const val SUBHEADING = 2
        const val QUOTE = 3
        const val BULLET = 4
        const val SMALL = 5
    }
}

object Blocks {
    private val INLINE = setOf(
        "a", "b", "strong", "i", "em", "u", "span", "br", "sup", "sub", "small", "big", "mark",
        "abbr", "cite", "code", "s", "strike", "del", "ins", "q", "font", "label", "time", "bdi", "bdo",
    )
    private val SKIP = setOf("script", "style", "noscript", "svg", "form", "button", "input", "select", "textarea", "nav", "template", "link", "meta")

    fun parse(html: String, base: String = SiteParse.BASE): List<Block> {
        if (html.isBlank()) return emptyList()
        val body = Jsoup.parseBodyFragment(html, base).body()
        val out = Walker(out = ArrayList())
        out.walk(body, Block.BODY)
        out.flush(Block.BODY)
        return tidy(out.out)
    }

    private fun tidy(blocks: List<Block>): List<Block> {
        val seenFiles = HashSet<String>()
        val seenImages = HashSet<String>()
        val result = ArrayList<Block>()
        for (b in blocks) {
            when (b) {
                is Block.File -> if (seenFiles.add(b.url)) result += b
                is Block.Image -> if (seenImages.add(b.url)) result += b
                is Block.Text -> {
                    // A paragraph that only repeats a file name next to its file block
                    val plain = Jsoup.parse(b.html).text().trim()
                    if (plain.isEmpty()) continue
                    if (plain == "הורדה" || plain == "Download") continue
                    result += b
                }
                else -> result += b
            }
        }
        return result
    }

    private class Walker(val out: ArrayList<Block>) {
        private val inline = StringBuilder()

        fun flush(style: Int) {
            val html = inline.toString().trim()
            inline.setLength(0)
            if (html.isEmpty()) return
            if (Jsoup.parse(html).text().isBlank()) return
            out += Block.Text(html, style)
        }

        private fun hasBlockInside(el: Element): Boolean =
            el.getAllElements().drop(1).any { it.tagName() !in INLINE || it.tagName() == "a" && it.selectFirst("img") != null } ||
                el.selectFirst("img") != null

        fun walk(el: Element, style: Int) {
            for (node in el.childNodes()) handle(node, style)
        }

        private fun imageUrl(img: Element): String {
            val src = img.absUrl("src")
            val lazy = img.absUrl("data-src").ifBlank { img.absUrl("data-lazy-src") }
            val best = if (src.isBlank() || src.startsWith("data:")) lazy else src
            if (best.isNotBlank()) return best
            val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
            return srcset.split(',').map { it.trim().substringBefore(' ') }.lastOrNull { it.isNotBlank() } ?: ""
        }

        private fun handle(node: Node, style: Int) {
            if (node is TextNode) {
                val t = node.outerHtml()
                if (t.isNotBlank() || inline.isNotEmpty()) inline.append(t)
                return
            }
            val el = node as? Element ?: return
            val tag = el.tagName().lowercase()
            if (tag in SKIP) return
            if (el.hasClass("screen-reader-text") || el.hasClass("elementor-screen-only")) return

            if (tag in INLINE && !hasBlockInside(el)) {
                inline.append(el.outerHtml())
                return
            }
            flush(style)
            when {
                el.hasClass("wp-block-file") -> {
                    val a = el.selectFirst("a[href]")
                    val url = a?.absUrl("href")?.ifBlank { null } ?: el.selectFirst("object[data]")?.absUrl("data") ?: ""
                    if (url.isNotBlank()) {
                        val name = a?.text()?.trim().orEmpty().ifBlank { url.substringAfterLast('/') }
                        out += Block.File(url, name)
                    }
                }
                tag == "h1" || tag == "h2" -> text(el, Block.HEADING)
                tag in setOf("h3", "h4", "h5", "h6") -> text(el, Block.SUBHEADING)
                tag == "p" || tag == "dd" || tag == "dt" || tag == "pre" -> {
                    if (hasMedia(el)) walk(el, style).also { flush(style) } else text(el, style)
                }
                tag == "li" -> {
                    if (el.children().any { it.tagName() in setOf("ul", "ol", "p", "div") } || hasMedia(el)) {
                        walk(el, Block.BULLET)
                        flush(Block.BULLET)
                    } else {
                        text(el, Block.BULLET)
                    }
                }
                tag == "blockquote" -> {
                    walk(el, Block.QUOTE)
                    flush(Block.QUOTE)
                }
                tag == "figcaption" -> text(el, Block.SMALL)
                tag == "img" -> {
                    val url = imageUrl(el)
                    if (url.isNotBlank() && !url.endsWith(".svg") && !isTiny(el)) out += Block.Image(url, el.attr("alt").trim())
                }
                tag == "iframe" -> {
                    val src = el.absUrl("src").ifBlank { el.absUrl("data-src") }.ifBlank { el.absUrl("data-lazy-src") }
                    val id = Links.youtubeId(src)
                    if (id != null) out += Block.YouTube(id, Links.youtubeStart(src))
                }
                tag == "video" -> {
                    val src = el.absUrl("src").ifBlank { el.selectFirst("source[src]")?.absUrl("src") ?: "" }
                    if (src.isNotBlank()) out += Block.Video(src)
                }
                tag == "audio" -> {
                    val src = el.absUrl("src").ifBlank { el.selectFirst("source[src]")?.absUrl("src") ?: "" }
                    if (src.isNotBlank()) out += Block.Audio(src)
                }
                tag == "object" -> {
                    val data = el.absUrl("data")
                    if (data.lowercase().endsWith(".pdf")) out += Block.File(data, data.substringAfterLast('/'))
                }
                tag == "table" -> {
                    for (row in el.select("tr")) {
                        val cells = row.select("th, td").map { it.html().trim() }.filter { it.isNotEmpty() }
                        if (cells.isNotEmpty()) out += Block.Text(cells.joinToString(" · "), style)
                    }
                }
                tag == "hr" || tag == "br" -> Unit
                else -> {
                    walk(el, style)
                    flush(style)
                }
            }
        }

        private fun hasMedia(el: Element): Boolean = el.selectFirst("img, iframe, video, audio, object") != null

        private fun isTiny(img: Element): Boolean {
            val w = img.attr("width").toIntOrNull() ?: return false
            return w in 1..40
        }

        private fun text(el: Element, style: Int) {
            val html = el.html().trim()
            if (Jsoup.parse(html).text().isNotBlank()) out += Block.Text(html, style)
        }
    }
}
