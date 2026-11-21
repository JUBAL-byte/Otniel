package app.jubal.otniel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the readers against markup shaped exactly like otniel.org's (Elementor + JetEngine). */
class ParserTest {

    private val seriesHtml = """
        <html><body>
        <div class="jet-select" data-query-var="vs_type"><select class="jet-select__control" name="vs_type">
          <option value="">בחר קטגורייה לסינון...</option><option value="ימי עיון">ימי עיון</option></select></div>
        <div class="jet-listing-grid jet-listing"><div class="jet-listing-grid__items">
        <div class="jet-listing-grid__item jet-listing-dynamic-post-17" data-post-id="17"><div class="jet-engine-listing-overlay-wrap"><div class="elementor elementor-5503"><div class="elementor-element e-parent">
          <div class="elementor-element elementor-widget elementor-widget-image"><img src="https://vangus-cdn.com/otniel.org/wp-content/uploads/2026/02/IMG_0974Min.webp" class="attachment-full size-full"></div>
          <div class="elementor-element vs-btn"><a class="elementor-button elementor-button-link elementor-size-sm" href="https://www.youtube.com/playlist?list=PL1ZOExsgbKmVs0dhQfDa4RtTTJvNgZ2Y7"><span class="elementor-button-content-wrapper"><span class="elementor-button-text">לצפיה בסדרה &gt;&gt;</span></span></a></div>
          <div class="elementor-element vs-links-cont"><div class="e-con-inner"><div class="elementor-widget"><div class="elementor-icon-box-wrapper"><div class="elementor-icon-box-content">
            <h3 class="elementor-icon-box-title"><span>לימוד מסכת זבחים</span></h3><p class="elementor-icon-box-description">הרב רא&quot;ם הכהן</p></div></div></div>
            <ul class="elementor-icon-list-items"><li class="elementor-icon-list-item elementor-inline-item"><span class="elementor-icon-list-text">80 שיעורים</span></li>
            <li class="elementor-icon-list-item elementor-inline-item"><span class="elementor-icon-list-text">שיעור כללי ועיון</span></li></ul></div></div>
          <a class="jet-engine-listing-overlay-link" href="https://www.youtube.com/playlist?list=PL1ZOExsgbKmVs0dhQfDa4RtTTJvNgZ2Y7"></a>
        </div></div></div></div>
        <div class="jet-listing-grid__item" data-post-id="58"><div class="elementor-element">
          <img src="https://vangus-cdn.com/otniel.org/wp-content/uploads/2026/02/x.webp">
          <a class="elementor-button elementor-size-sm" href=""><span class="elementor-button-text">לצפיה בסדרה &gt;&gt;</span></a>
          <h3 class="elementor-icon-box-title"><span>פרשת בהר</span></h3><p class="elementor-icon-box-description">ספר ויקרא</p>
          <ul><li class="elementor-icon-list-item"><span class="elementor-icon-list-text">10 שיעורים</span></li><li><span class="elementor-icon-list-text">פרשת השבוע</span></li></ul>
        </div></div>
        </div></div></body></html>
    """.trimIndent()

    @Test
    fun series() {
        val list = SiteParse.series(seriesHtml)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("לימוד מסכת זבחים", first.title)
        assertEquals("הרב רא\"ם הכהן", first.subtitle)
        assertEquals(80, first.count)
        assertEquals("שיעור כללי ועיון", first.category)
        assertEquals("PL1ZOExsgbKmVs0dhQfDa4RtTTJvNgZ2Y7", Links.youtubePlaylist(first.playlistUrl))
        assertTrue(first.image.endsWith("IMG_0974Min.webp"))
        assertEquals("", list[1].playlistUrl)
        assertEquals(10, list[1].count)
        assertEquals("פרשת השבוע", list[1].category)
    }

    private val podcastsHtml = """
        <div class="jet-listing-grid"><div class="jet-listing-grid__items">
        <div class="jet-listing-grid__item" data-post-id="26"><style>.x{}</style><div class="elementor">
          <div class="elementor-widget-image"><img src="/wp-content/uploads/2026/01/a.webp" alt=""></div>
          <h2 class="elementor-heading-title elementor-size-default">יהדות ומדיטציה</h2>
          <div class="jet-listing-dynamic-field__content">הרב איל אלפיה</div>
          <a class="elementor-icon" href="https://open.spotify.com/show/2ewWKhdiyctZT93egnnA1y?si=adfd04bc3d5f4d69"><svg></svg></a>
          <a class="elementor-icon" href="https://www.youtube.com/playlist?list=PL1ZOExsgbKmXjBV_iJ6CN9D0WwJg-K84X"><svg></svg></a>
          <a class="elementor-icon" href="https://podcasts.apple.com/il/podcast/x/id1654390049?l=he"><svg></svg></a>
        </div></div>
        <div class="jet-listing-grid__item" data-post-id="1"><div class="elementor">
          <img src="https://vangus-cdn.com/otniel.org/wp-content/uploads/2026/01/b.webp">
          <a class="elementor-icon" href="https://open.spotify.com/show/3UjpbT589EycIXTyGGvlWh?si=93d3"><svg></svg></a>
        </div></div>
        </div></div>
    """.trimIndent()

    @Test
    fun shows() {
        val list = SiteParse.shows(podcastsHtml)
        assertEquals(2, list.size)
        assertEquals("יהדות ומדיטציה", list[0].title)
        assertEquals("הרב איל אלפיה", list[0].presenter)
        assertEquals("https://open.spotify.com/show/2ewWKhdiyctZT93egnnA1y", list[0].spotify)
        assertEquals(1654390049L, Links.appleId(list[0].apple))
        assertEquals("PL1ZOExsgbKmXjBV_iJ6CN9D0WwJg-K84X", Links.youtubePlaylist(list[0].youtube))
        assertEquals("https://otniel.org/wp-content/uploads/2026/01/a.webp", list[0].image)
        assertEquals("", list[1].title)
        assertEquals("", list[1].apple)
    }

    private val lessonPage = """
        <html><body><header data-elementor-type="header"><div class="elementor-widget-video" data-settings="{&quot;youtube_url&quot;:&quot;https:\/\/www.youtube.com\/watch?v=HEADERxxxxx&quot;}"></div></header>
        <div data-elementor-type="single-post" data-elementor-id="6464">
          <div class="elementor-widget-theme-post-title"><h1>שיעור כללי</h1></div>
          <div class="elementor-element elementor-widget elementor-widget-image"><img src="https://vangus-cdn.com/otniel.org/wp-content/uploads/2026/01/harav-reem-hacohen.webp"></div>
          <div class="elementor-element elementor-widget elementor-widget-video" data-settings="{&quot;youtube_url&quot;:&quot;https:\/\/www.youtube.com\/watch?v=JBoALZVdu4E&quot;,&quot;video_type&quot;:&quot;youtube&quot;,&quot;controls&quot;:&quot;yes&quot;}" data-widget_type="video.default"></div>
          <div class="jet-listing-grid"><div class="jet-listing-grid__item"><div class="elementor-widget-video" data-settings="{&quot;youtube_url&quot;:&quot;https:\/\/youtu.be\/OTHERxxxxxx&quot;}"></div></div></div>
        </div></body></html>
    """.trimIndent()

    @Test
    fun lessonVideo() {
        assertEquals("JBoALZVdu4E", SiteParse.lessonMedia(lessonPage).youtubeId)
        assertTrue(SiteParse.lessonMedia(lessonPage).rabbiImage.endsWith("harav-reem-hacohen.webp"))
        val hosted = """<div data-elementor-type="single-post"><div class="elementor-widget-video" data-settings="{&quot;video_type&quot;:&quot;hosted&quot;,&quot;hosted_url&quot;:{&quot;url&quot;:&quot;https:\/\/otniel.org\/wp-content\/uploads\/2026\/11\/a.mp4&quot;,&quot;id&quot;:5}}"></div></div>"""
        assertEquals("https://otniel.org/wp-content/uploads/2026/11/a.mp4", SiteParse.lessonMedia(hosted).hostedUrl)
        assertTrue(!SiteParse.lessonMedia("<div data-elementor-type=\"single-post\"><p>טקסט</p></div>").hasVideo)
    }

    private val faqPage = """
        <div data-elementor-type="single-post">
          <div class="elementor-widget-image-box">שו"ת</div>
          <div class="elementor-widget elementor-widget-heading"><h2 class="elementor-heading-title">זכר לחורבן בבניית בית</h2></div>
          <div class="elementor-widget elementor-widget-heading"><h2 class="elementor-heading-title">הרב רא"ם הכהן</h2></div>
          <div class="elementor-widget elementor-widget-heading"><h2 class="elementor-heading-title">שאלה:</h2></div>
          <div class="elementor-widget elementor-widget-heading"><h2 class="elementor-heading-title">האם בבניית בית צריך לשייר "זכר לחורבן"?</h2></div>
          <div class="elementor-widget elementor-widget-heading"><h2 class="elementor-heading-title">תשובה:</h2></div>
          <div class="elementor-widget elementor-widget-heading"><div class="elementor-heading-title">מקור הדין במסכת בבא בתרא<br>ועוד</div></div>
          <div class="elementor-widget elementor-widget-jet-listing-grid"><div class="jet-listing-grid"><div class="elementor-widget-heading"><h2>שאלה אחרת</h2></div></div></div>
        </div>
    """.trimIndent()

    @Test
    fun faq() {
        val a = SiteParse.faqAnswer(faqPage)
        assertEquals("האם בבניית בית צריך לשייר \"זכר לחורבן\"?", a.question)
        assertTrue(a.answerHtml.contains("מקור הדין"))
        assertTrue(!a.answerHtml.contains("שאלה אחרת"))
    }

    @Test
    fun restLessons() {
        val json = """[{"id":7732,"date":"2026-11-12T06:37:40","slug":"%d7%a9","link":"https:\/\/otniel.org\/shiurim\/%d7%a9\/",
            "title":{"rendered":"שיעור כללי: בבא מציעא | בין חלוקה &#8211; לכל &quot;דאלים&quot; גבר"},
            "content":{"rendered":"","protected":false},"excerpt":{"rendered":"<p>שיחה על אחדות&#8230;<\/p>\n"},
            "featured_media":0,"rabbis":[406],"topics":[132,116]}]"""
        val l = SiteParse.lessons(json).single()
        assertEquals(7732L, l.id)
        assertEquals("שיעור כללי: בבא מציעא | בין חלוקה – לכל \"דאלים\" גבר", l.title)
        assertEquals("https://otniel.org/shiurim/%d7%a9/", l.link)
        assertEquals(listOf(406L), l.rabbis)
        assertEquals(listOf(132L, 116L), l.topics)
        assertEquals("שיחה על אחדות…", l.excerpt)
    }

    @Test
    fun restBooks() {
        val json = """[{"id":7522,"name":"שיעורי הרב ראם יומא ב'","permalink":"https:\/\/otniel.org\/product\/x\/",
          "prices":{"price":"4500","regular_price":"5500","sale_price":"4500","currency_code":"ILS","currency_symbol":"₪","currency_minor_unit":2},
          "is_purchasable":true,"is_in_stock":true,"images":[{"id":1,"src":"https:\/\/otniel.org\/a.webp"}],"description":"<p>ספר<\/p>"}]"""
        val b = SiteParse.books(json).single()
        assertEquals("45 ₪", b.money(b.price))
        assertEquals("55 ₪", b.money(b.regularPrice))
        assertTrue(b.onSale)
        assertEquals(listOf("https://otniel.org/a.webp"), b.images)
    }

    @Test
    fun json() {
        val j = J.parse("""{"a":[1,2.5,"xא\n",true,null,{"b":-3e2}],"c":"\"q\""}""")
        assertEquals(1L, j["a"][0].long())
        assertEquals("2.5", j["a"][1].str())
        assertEquals("xא\n", j["a"][2].str())
        assertTrue(j["a"][3].bool())
        assertTrue(j["a"][4].isNull)
        assertEquals(-300L, j["a"][5]["b"].long())
        assertEquals("\"q\"", j["c"].str())
        assertTrue(j["missing"]["deeper"].isNull)
    }

    @Test
    fun links() {
        assertEquals(Target.Video("rYDzoIPBT6M", 74), Links.target("https://www.youtube.com/watch?v=rYDzoIPBT6M&amp;t=74"))
        assertEquals(Target.Video("rYDzoIPBT6M", 74), Links.target("https://youtu.be/rYDzoIPBT6M?t=1m14s"))
        assertEquals("JBoALZVdu4E", Links.youtubeId("https:\\/\\/www.youtube.com\\/watch?v=JBoALZVdu4E"))
        assertTrue(Links.target("https://www.youtube.com/playlist?list=PL1ZOExsgbKmVs0dhQfDa4RtTTJvNgZ2Y7") is Target.Playlist)
        assertEquals(
            Target.LessonSlug("שיעור-כללי"),
            Links.target("https://otniel.org/shiurim/%d7%a9%d7%99%d7%a2%d7%95%d7%a8-%d7%9b%d7%9c%d7%9c%d7%99/"),
        )
        assertEquals(Target.Topic("tanach"), Links.target("https://otniel.org/topics/tanach/"))
        assertEquals(Target.Rabbi("harav-reem-hacohen"), Links.target("https://www.otniel.org/rabbis/harav-reem-hacohen/"))
        assertEquals(Target.Tab(Links.TAB_PODCASTS), Links.target("https://otniel.org/all-podcasts/"))
        assertEquals(Target.FaqList, Links.target("https://otniel.org/%d7%a9%d7%95%d7%aa/"))
        assertEquals(Target.Home, Links.target("https://otniel.org/"))
        assertTrue(Links.target("https://otniel.org/cart/") is Target.Browser)
        assertTrue(Links.target("https://otniel.org/?add-to-cart=211") is Target.Browser)
        assertTrue(Links.target("https://otniel.org/wp-content/uploads/2026/11/a.pdf") is Target.Pdf)
        assertTrue(Links.target("https://otniel.org/wp-content/uploads/2026/11/a.webm") is Target.Media)
        assertTrue(Links.target("https://lp.vp4.me/syin") is Target.External)
        assertTrue(Links.target("tel:029964241") is Target.Phone)
        assertEquals(Target.Product("x"), Links.target("https://otniel.org/product/x/"))
        assertTrue(Links.target("/about/") is Target.About)
    }

    @Test
    fun blocks() {
        val html = """
            <h2>כותרת</h2><p>פסקה עם <a href="https://www.youtube.com/watch?v=rYDzoIPBT6M&amp;t=74">קישור</a> ו<strong>הדגשה</strong></p>
            <figure class="wp-block-image"><img src="https://vangus-cdn.com/a.webp" alt="תמונה"></figure>
            <figure class="wp-block-video"><video src="https://otniel.org/wp-content/uploads/2026/11/t.webm" controls></video></figure>
            <div class="wp-block-file"><object class="wp-block-file__embed" data="https://otniel.org/x/a.pdf" type="application/pdf"></object>
              <a href="https://otniel.org/x/a.pdf">שיעור 01</a><a class="wp-block-file__button" href="https://otniel.org/x/a.pdf" download>הורדה</a></div>
            <ul><li>אחד</li><li>שניים</li></ul><blockquote><p>ציטוט</p></blockquote>
            <iframe src="https://www.youtube.com/embed/JBoALZVdu4E?start=30"></iframe>
        """.trimIndent()
        val b = Blocks.parse(html)
        assertEquals(Block.Text("כותרת", Block.HEADING), b[0])
        assertTrue((b[1] as Block.Text).html.contains("<a href="))
        assertEquals(Block.Image("https://vangus-cdn.com/a.webp", "תמונה"), b[2])
        assertEquals(Block.Video("https://otniel.org/wp-content/uploads/2026/11/t.webm"), b[3])
        assertEquals(Block.File("https://otniel.org/x/a.pdf", "שיעור 01"), b[4])
        assertEquals(Block.Text("אחד", Block.BULLET), b[5])
        assertEquals(Block.Text("שניים", Block.BULLET), b[6])
        assertEquals(Block.Text("ציטוט", Block.QUOTE), b[7])
        assertEquals(Block.YouTube("JBoALZVdu4E", 30), b[8])
        assertEquals(9, b.size)
    }

    @Test
    fun hebrewNumbers() {
        assertEquals("כ״א", Hebrew.number(21))
        assertEquals("ט״ו", Hebrew.number(15))
        assertEquals("ט״ז", Hebrew.number(16))
        assertEquals("א׳", Hebrew.number(1))
        assertEquals("תשפ״ז", Hebrew.number(5787))
        assertEquals("ל׳", Hebrew.number(30))
        assertEquals("כ״א בכסלו תשפ״ז", Hebrew.date(21, 2, 5787, false))
    }
}
