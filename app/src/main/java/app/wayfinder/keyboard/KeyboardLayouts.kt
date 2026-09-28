package app.wayfinder.keyboard

import java.util.Locale

/**
 * The keyboard layouts. Each layout is its letter rows (space-separated keys),
 * the long-press alternates that matter for its language first, and the currency
 * shown on the symbols page. The bottom row and the symbol pages are shared.
 *
 * Covered: the most-used alphabetic layouts. Languages that need a conversion engine
 * (Chinese, Japanese, Korean, Hindi/Indic, Vietnamese Telex) are not here yet.
 */
data class KbLayout(
    val id: String,
    /** Native name (settings list). */
    val name: String,
    /** Short label shown on the space bar. */
    val short: String,
    /** BCP-47 language tags that auto-enable this layout (first = the one used for casing). */
    val languages: List<String>,
    val rows: List<List<String>>,
    /** Long-press alternates for this language, merged in front of the shared ones. */
    val alternates: Map<String, String> = emptyMap(),
    val currency: String = "$",
    /** Scripts without upper case (Arabic, Hebrew) hide the shift key. */
    val cased: Boolean = true,
) {
    val locale: Locale get() = Locale.forLanguageTag(languages.first())

    /** The small hint on a key: a digit, or this language's own first accent (never noise like ð on English). */
    fun hintFor(key: String): String? {
        val own = alternates[key].orEmpty().split(' ').firstOrNull { it.isNotBlank() }
        val shared = SHARED_ALTERNATES[key].orEmpty().split(' ').firstOrNull { it.isNotBlank() }
        return own ?: shared?.takeIf { it.length == 1 && it[0].isDigit() }
    }

    /** Long-press options for [key] (lower-case), language-specific first, no duplicates. */
    fun alternatesFor(key: String): List<String> {
        val own = alternates[key].orEmpty().split(' ').filter { it.isNotBlank() }
        val shared = SHARED_ALTERNATES[key].orEmpty().split(' ').filter { it.isNotBlank() }
        return (own + shared).distinct().filter { it != key }
    }
}

private fun rows(vararg r: String) = r.map { row -> row.trim().split(Regex("\\s+")) }

/** Latin accents any Latin layout can reach by long-press; digits on the top row. */
private val SHARED_ALTERNATES = mapOf(
    "q" to "1", "w" to "2", "e" to "3 é è ê ë ē ę ė", "r" to "4", "t" to "5 þ",
    "y" to "6 ý ÿ", "u" to "7 ù ú û ü ū", "i" to "8 í ì î ï ī į ı", "o" to "9 ó ò ô ö õ ø œ ō",
    "p" to "0", "a" to "à á â ä æ ã å ā ą", "s" to "ß ś š", "d" to "ð", "g" to "ğ",
    "l" to "ł", "z" to "ž ź ż", "c" to "ç ć č", "n" to "ñ ń",
    "." to ", ? ! ' \" : ; - … @ #", "," to "; : …",
)

private const val QWERTY_1 = "q w e r t y u i o p"
private const val QWERTY_2 = "a s d f g h j k l"
private const val QWERTY_3 = "z x c v b n m"

val ALL_LAYOUTS: List<KbLayout> = listOf(
    KbLayout("en_US", "English (US)", "EN", listOf("en"), rows(QWERTY_1, QWERTY_2, QWERTY_3)),
    KbLayout(
        "fr_CA", "Français (Canada)", "FR-CA", listOf("fr-CA"), rows(QWERTY_1, QWERTY_2, QWERTY_3),
        alternates = mapOf("e" to "é è ê ë", "a" to "à â", "c" to "ç", "u" to "ù û ü", "i" to "î ï", "o" to "ô œ",
            "y" to "ÿ", "." to "« » ' - ? !"),
    ),
    KbLayout(
        "fr_FR", "Français (AZERTY)", "FR", listOf("fr"),
        rows("a z e r t y u i o p", "q s d f g h j k l m", "w x c v b n '"),
        alternates = mapOf("e" to "é è ê ë €", "a" to "1 à â æ", "c" to "ç", "u" to "ù û ü", "i" to "î ï", "o" to "ô œ",
            "z" to "2", "y" to "6 ÿ", "'" to "’ « » \""),
        currency = "€",
    ),
    KbLayout(
        "es", "Español", "ES", listOf("es"), rows(QWERTY_1, "a s d f g h j k l ñ", QWERTY_3),
        alternates = mapOf("e" to "é", "a" to "á", "i" to "í", "o" to "ó", "u" to "ú ü", "." to "¿ ¡ ? !"),
        currency = "€",
    ),
    KbLayout(
        "pt_BR", "Português", "PT", listOf("pt"), rows(QWERTY_1, "a s d f g h j k l ç", QWERTY_3),
        alternates = mapOf("a" to "ã á â à", "e" to "é ê", "i" to "í", "o" to "õ ó ô", "u" to "ú ü", "c" to "ç"),
        currency = "R$",
    ),
    KbLayout(
        "de", "Deutsch (QWERTZ)", "DE", listOf("de"),
        rows("q w e r t z u i o p ü", "a s d f g h j k l ö ä", "y x c v b n m"),
        alternates = mapOf("s" to "ß", "a" to "ä", "o" to "ö", "u" to "ü", "z" to "6", "y" to "ý"),
        currency = "€",
    ),
    KbLayout(
        "it", "Italiano", "IT", listOf("it"), rows(QWERTY_1, QWERTY_2, QWERTY_3),
        alternates = mapOf("e" to "è é", "a" to "à", "i" to "ì", "o" to "ò", "u" to "ù"),
        currency = "€",
    ),
    KbLayout(
        "sv", "Svenska / Suomi", "SV", listOf("sv", "fi"),
        rows("q w e r t y u i o p å", "a s d f g h j k l ö ä", QWERTY_3),
        currency = "kr",
    ),
    KbLayout(
        "nb", "Norsk", "NO", listOf("nb", "no", "nn"),
        rows("q w e r t y u i o p å", "a s d f g h j k l ø æ", QWERTY_3),
        currency = "kr",
    ),
    KbLayout(
        "da", "Dansk", "DA", listOf("da"),
        rows("q w e r t y u i o p å", "a s d f g h j k l æ ø", QWERTY_3),
        currency = "kr",
    ),
    KbLayout(
        "pl", "Polski", "PL", listOf("pl"), rows(QWERTY_1, QWERTY_2, QWERTY_3),
        alternates = mapOf("a" to "ą", "c" to "ć", "e" to "ę", "l" to "ł", "n" to "ń", "o" to "ó", "s" to "ś", "z" to "ż ź"),
        currency = "zł",
    ),
    KbLayout(
        "tr", "Türkçe", "TR", listOf("tr"),
        rows("q w e r t y u ı o p ğ ü", "a s d f g h j k l ş i", "z x c v b n m ö ç"),
        currency = "₺",
    ),
    KbLayout(
        "ru", "Русский", "RU", listOf("ru"),
        rows("й ц у к е н г ш щ з х", "ф ы в а п р о л д ж э", "я ч с м и т ь б ю"),
        alternates = mapOf("е" to "ё", "ь" to "ъ"),
        currency = "₽",
    ),
    KbLayout(
        "uk", "Українська", "UK", listOf("uk"),
        rows("й ц у к е н г ш щ з х ї", "ф і в а п р о л д ж є", "я ч с м и т ь б ю"),
        alternates = mapOf("г" to "ґ", "і" to "ї", "ь" to "'"),
        currency = "₴",
    ),
    KbLayout(
        "el", "Ελληνικά", "EL", listOf("el"),
        rows("; ς ε ρ τ υ θ ι ο π", "α σ δ φ γ η ξ κ λ", "ζ χ ψ ω β ν μ"),
        alternates = mapOf("ε" to "έ", "α" to "ά", "η" to "ή", "ι" to "ί ϊ ΐ", "ο" to "ό", "υ" to "ύ ϋ ΰ", "ω" to "ώ"),
        currency = "€",
    ),
    KbLayout(
        "ar", "العربية", "ع", listOf("ar"),
        rows("ض ص ث ق ف غ ع ه خ ح ج", "ش س ي ب ل ا ت ن م ك ط", "ذ ء ؤ ر ى ة و ز ظ د"),
        alternates = mapOf("ا" to "أ إ آ ٱ", "ي" to "ئ", "ه" to "ة", "." to "، ؟ ؛"),
        cased = false,
    ),
    KbLayout(
        "he", "עברית", "עב", listOf("he", "iw"),
        rows("ק ר א ט ו ן ם פ", "ש ד ג כ ע י ח ל ך ף", "ז ס ב ה נ מ צ ת ץ"),
        currency = "₪", cased = false,
    ),
)

fun layoutById(id: String): KbLayout? = ALL_LAYOUTS.firstOrNull { it.id == id }

/**
 * Plug-and-play default: the layouts matching the device's languages (in the user's
 * order), plus English. A Québec device (fr-CA) gets the Canadian French QWERTY, not
 * AZERTY — region tags are matched before bare languages.
 */
fun defaultLayoutIds(locales: List<Locale>): List<String> {
    val out = LinkedHashSet<String>()
    for (loc in locales) {
        val tag = loc.toLanguageTag()
        val exact = ALL_LAYOUTS.firstOrNull { l -> l.languages.any { it.equals(tag, true) || (it.contains('-') && tag.startsWith(it, true)) } }
        val lang = exact ?: ALL_LAYOUTS.firstOrNull { l -> l.languages.any { !it.contains('-') && it.equals(loc.language, true) } }
        lang?.let { out.add(it.id) }
    }
    out.add("en_US")
    return out.toList()
}

/** Symbols pages (shared). "\$CUR" = the layout's currency. */
val SYMBOLS_1 = rows("1 2 3 4 5 6 7 8 9 0", "@ # \$CUR _ & - + ( ) /", "* \" ' : ; ! ?")
val SYMBOLS_2 = rows("~ ` | < > • √ π ÷ ×", "€ £ ¥ ¢ ^ ° = { } \\", "% © ® ™ ✓ [ ]")

/** 1.3.2 (GitHub #48): emoji groups — each 3 rows of 10 (name, rows). */
val EMOJI_GROUPS: List<Pair<String, List<List<String>>>> = listOf(
    "😀" to rows("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇", "🙂 😉 😍 🥰 😘 😋 😜 🤔 😎 🥳", "😐 🙄 😏 😢 😭 😤 😡 😱 😴 🤯"),
    "👍" to rows("👍 👎 👌 ✌️ 🤞 🤘 👏 🙌 🙏 💪", "👋 🤝 👀 🫶 🙈 🤷 🤦 🙋 🎉 🔥", "💯 ✨ ⭐ 💥 💤 💬 ✅ ❌ ❗ ❓"),
    "❤️" to rows("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕", "😺 🐶 🐱 🐭 🦊 🐻 🐼 🐸 🐧 🦄", "🌞 🌙 ⭐ 🌈 ☁️ ⚡ ❄️ 🌊 🌸 🍀"),
    "🎮" to rows("🎮 🕹️ 👾 🏆 🥇 🎯 🎲 🧩 🎵 🎧", "📱 💻 🖥️ ⌨️ 🔋 🔌 📷 🎬 📺 💾", "⏰ 🚀 🚗 ✈️ 🏠 🎁 💡 🔑 🛠️ 💰"),
    "🍕" to rows("🍕 🍔 🍟 🌭 🌮 🍣 🍜 🍩 🍪 🎂", "🍎 🍌 🍓 🍉 🍒 🥑 🥕 🌽 🧀 🥚", "☕ 🍵 🥤 🍺 🍷 🥂 🍾 🧃 🥛 🍫"),
)
