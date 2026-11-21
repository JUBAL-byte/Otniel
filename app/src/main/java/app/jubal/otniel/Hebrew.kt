package app.jubal.otniel

/** Hebrew numerals (gematria) for dates: 21 → כ״א, 5787 → תשפ״ז. */
object Hebrew {
    private val ONES = arrayOf("", "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט")
    private val TENS = arrayOf("", "י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ")
    private val HUNDREDS = arrayOf("", "ק", "ר", "ש")

    fun number(value: Int): String {
        var n = value % 1000
        if (n <= 0) return value.toString()
        val sb = StringBuilder()
        while (n >= 400) {
            sb.append("ת")
            n -= 400
        }
        if (n >= 100) {
            sb.append(HUNDREDS[n / 100])
            n %= 100
        }
        when (n) {
            15 -> sb.append("טו")
            16 -> sb.append("טז")
            else -> {
                sb.append(TENS[n / 10])
                sb.append(ONES[n % 10])
            }
        }
        val s = sb.toString()
        return if (s.length == 1) "$s׳" else s.substring(0, s.length - 1) + "״" + s.last()
    }

    /** Hebrew month names by the numbering of ICU's HebrewCalendar (0 = Tishrei ... 12 = Elul). */
    fun month(index: Int, leap: Boolean): String = when (index) {
        0 -> "תשרי"
        1 -> "חשוון"
        2 -> "כסלו"
        3 -> "טבת"
        4 -> "שבט"
        5 -> "אדר א׳"
        6 -> if (leap) "אדר ב׳" else "אדר"
        7 -> "ניסן"
        8 -> "אייר"
        9 -> "סיוון"
        10 -> "תמוז"
        11 -> "אב"
        12 -> "אלול"
        else -> ""
    }

    fun date(day: Int, monthIndex: Int, year: Int, leap: Boolean, withYear: Boolean = true): String {
        val base = "${number(day)} ב${month(monthIndex, leap)}"
        return if (withYear) "$base ${number(year)}" else base
    }
}
