package com.pocketai.app.voice

import java.util.Locale

/**
 * Works out which language a spoken sentence was in, offline.
 *
 * Android 13 and newer can report this from the recogniser itself, which is
 * better and is preferred when present. This exists for everything else: older
 * phones, recognisers that do not fill the field in, and text the user typed.
 *
 * Two rules keep it from doing damage:
 *
 * 1. Non-Latin scripts are decisive. Cyrillic is not English, ever.
 * 2. For Latin scripts it returns null unless one language wins clearly. A
 *    conversation that flips to Dutch because someone said "dat" is worse than
 *    one that stays where it was, so an unsure answer is no answer.
 */
object LanguageDetector {

    /** Minimum weighted score before any Latin-script guess is offered at all. */
    private const val MIN_SCORE = 3.0

    /** How far ahead the winner must be before it counts as a clear win. */
    private const val MARGIN = 1.5

    fun detect(text: String): SpokenLanguage? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        byScript(trimmed)?.let { return it }
        return byVocabulary(trimmed)
    }

    /**
     * Scripts that belong to exactly one of the supported languages.
     *
     * Counts characters rather than testing the first one: a German sentence
     * quoting a Greek word is still German.
     */
    private fun byScript(text: String): SpokenLanguage? {
        var latin = 0; var cyrillic = 0; var greek = 0; var arabic = 0
        var hebrew = 0; var devanagari = 0; var thai = 0
        var hiraganaKatakana = 0; var hangul = 0; var han = 0

        for (ch in text) {
            if (!ch.isLetter()) continue
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_B,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> latin++

                Character.UnicodeBlock.CYRILLIC,
                Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> cyrillic++

                Character.UnicodeBlock.GREEK,
                Character.UnicodeBlock.GREEK_EXTENDED -> greek++

                Character.UnicodeBlock.ARABIC,
                Character.UnicodeBlock.ARABIC_SUPPLEMENT -> arabic++

                Character.UnicodeBlock.HEBREW -> hebrew++
                Character.UnicodeBlock.DEVANAGARI -> devanagari++
                Character.UnicodeBlock.THAI -> thai++

                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA -> hiraganaKatakana++

                Character.UnicodeBlock.HANGUL_SYLLABLES,
                Character.UnicodeBlock.HANGUL_JAMO,
                Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> hangul++

                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A -> han++

                else -> Unit
            }
        }

        // Kana settles Japanese even in a sentence that is mostly kanji; without
        // any kana, shared Han characters are Chinese.
        if (hiraganaKatakana > 0) return SpokenLanguage.JAPANESE
        if (hangul > 0) return SpokenLanguage.KOREAN

        val nonLatin = listOf(
            cyrillic to SpokenLanguage.RUSSIAN,
            greek to SpokenLanguage.GREEK,
            arabic to SpokenLanguage.ARABIC,
            hebrew to SpokenLanguage.HEBREW,
            devanagari to SpokenLanguage.HINDI,
            thai to SpokenLanguage.THAI,
            han to SpokenLanguage.CHINESE
        ).maxByOrNull { it.first } ?: return null

        // Ukrainian shares Cyrillic with Russian; a few letters exist only in it.
        if (nonLatin.second == SpokenLanguage.RUSSIAN && text.any { it in UKRAINIAN_ONLY }) {
            return SpokenLanguage.UKRAINIAN
        }
        return if (nonLatin.first > latin) nonLatin.second else null
    }

    private fun byVocabulary(text: String): SpokenLanguage? {
        val words = text.lowercase(Locale.ROOT)
            .split(NON_WORD)
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val scores = HashMap<SpokenLanguage, Double>()
        for (word in words) {
            for ((language, vocabulary) in VOCABULARY) {
                vocabulary[word]?.let { scores[language] = (scores[language] ?: 0.0) + it }
            }
        }
        // Characters that only a few languages use at all.
        for (ch in text.lowercase(Locale.ROOT)) {
            CHAR_SIGNALS[ch]?.forEach { (language, weight) ->
                scores[language] = (scores[language] ?: 0.0) + weight
            }
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        if (best.value < MIN_SCORE) return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
        return if (best.value >= runnerUp * MARGIN) best.key else null
    }

    private val NON_WORD = Regex("[^\\p{L}]+")

    private val UKRAINIAN_ONLY = setOf('і', 'ї', 'є', 'ґ', 'І', 'Ї', 'Є', 'Ґ')

    /** Letters that narrow the field sharply on their own. */
    private val CHAR_SIGNALS: Map<Char, List<Pair<SpokenLanguage, Double>>> = mapOf(
        'ß' to listOf(SpokenLanguage.GERMAN to 3.0),
        'ä' to listOf(SpokenLanguage.GERMAN to 1.0, SpokenLanguage.FINNISH to 1.0, SpokenLanguage.SWEDISH to 0.8),
        'ö' to listOf(SpokenLanguage.GERMAN to 1.0, SpokenLanguage.SWEDISH to 0.8, SpokenLanguage.FINNISH to 0.8, SpokenLanguage.TURKISH to 0.6),
        'ü' to listOf(SpokenLanguage.GERMAN to 1.0, SpokenLanguage.TURKISH to 0.8, SpokenLanguage.HUNGARIAN to 0.5),
        'ñ' to listOf(SpokenLanguage.SPANISH to 3.0),
        'ç' to listOf(SpokenLanguage.FRENCH to 1.2, SpokenLanguage.PORTUGUESE to 1.2, SpokenLanguage.TURKISH to 1.0),
        'ã' to listOf(SpokenLanguage.PORTUGUESE to 2.5),
        'õ' to listOf(SpokenLanguage.PORTUGUESE to 2.0),
        'ê' to listOf(SpokenLanguage.FRENCH to 1.0, SpokenLanguage.PORTUGUESE to 1.0),
        'è' to listOf(SpokenLanguage.FRENCH to 1.2, SpokenLanguage.ITALIAN to 1.0),
        'à' to listOf(SpokenLanguage.FRENCH to 0.8, SpokenLanguage.ITALIAN to 0.8, SpokenLanguage.PORTUGUESE to 0.8),
        'ù' to listOf(SpokenLanguage.FRENCH to 1.0, SpokenLanguage.ITALIAN to 1.0),
        'ł' to listOf(SpokenLanguage.POLISH to 3.0),
        'ż' to listOf(SpokenLanguage.POLISH to 2.5),
        'ś' to listOf(SpokenLanguage.POLISH to 2.0),
        'ę' to listOf(SpokenLanguage.POLISH to 2.5),
        'ą' to listOf(SpokenLanguage.POLISH to 2.5),
        'ř' to listOf(SpokenLanguage.CZECH to 3.0),
        'ě' to listOf(SpokenLanguage.CZECH to 3.0),
        'ů' to listOf(SpokenLanguage.CZECH to 3.0),
        'ô' to listOf(SpokenLanguage.SLOVAK to 1.5, SpokenLanguage.FRENCH to 0.8),
        'ľ' to listOf(SpokenLanguage.SLOVAK to 3.0),
        'ő' to listOf(SpokenLanguage.HUNGARIAN to 3.0),
        'ű' to listOf(SpokenLanguage.HUNGARIAN to 3.0),
        'ș' to listOf(SpokenLanguage.ROMANIAN to 3.0),
        'ț' to listOf(SpokenLanguage.ROMANIAN to 3.0),
        'ı' to listOf(SpokenLanguage.TURKISH to 2.5),
        'ğ' to listOf(SpokenLanguage.TURKISH to 3.0),
        'ş' to listOf(SpokenLanguage.TURKISH to 2.0),
        'å' to listOf(SpokenLanguage.SWEDISH to 2.0, SpokenLanguage.DANISH to 1.5, SpokenLanguage.NORWEGIAN to 1.5),
        'ø' to listOf(SpokenLanguage.DANISH to 2.5, SpokenLanguage.NORWEGIAN to 2.5),
        'æ' to listOf(SpokenLanguage.DANISH to 2.5, SpokenLanguage.NORWEGIAN to 2.0),
        'ơ' to listOf(SpokenLanguage.VIETNAMESE to 3.0),
        'ư' to listOf(SpokenLanguage.VIETNAMESE to 3.0),
        'ĩ' to listOf(SpokenLanguage.VIETNAMESE to 2.5),
        'ị' to listOf(SpokenLanguage.VIETNAMESE to 3.0)
    )

    /** Weight 3 words are near-unique to their language; weight 1 words are merely typical. */
    private val VOCABULARY: Map<SpokenLanguage, Map<String, Double>> = buildMap {
        fun add(language: SpokenLanguage, strong: String, common: String) {
            val map = HashMap<String, Double>()
            common.split(' ').filter { it.isNotBlank() }.forEach { map[it] = 1.0 }
            strong.split(' ').filter { it.isNotBlank() }.forEach { map[it] = 3.0 }
            put(language, map)
        }

        add(
            SpokenLanguage.ENGLISH,
            strong = "the and is are you what which because their there they're doesn't wouldn't",
            common = "a an of to in it for on with that this have has was were will would can could i me my we he she but or if not do does did about would please thanks thank how why when where who"
        )
        add(
            SpokenLanguage.GERMAN,
            strong = "und ist nicht ich du das der die dass weil aber auch noch schon wie warum wer wo danke bitte gut sehr können haben sein werden wurde deutsch",
            common = "ein eine mit auf für von zu dem den des als bei nach über um dann jetzt hier mehr wir ihr sie es was wenn oder man so nur muss soll kann"
        )
        add(
            SpokenLanguage.DUTCH,
            strong = "het een niet ik je we zijn hebben omdat waarom alsjeblieft dank nederlands maar ook nog hoe wat wanneer waar wie goed heel",
            common = "de en is met op voor van naar dan nu hier meer worden dat als of bij kan moet zal mijn jij hij zij"
        )
        add(
            SpokenLanguage.FRENCH,
            strong = "le la les des une est pas je tu nous vous ils elles c'est qu'est-ce pourquoi merci s'il bonjour français très",
            common = "un et de à en que qui pour dans sur avec ce cette mais ou si plus comment quand où bien faire être avoir"
        )
        add(
            SpokenLanguage.SPANISH,
            strong = "el la los las una está qué por qué gracias hola español muy pero también cómo cuándo dónde quién porque",
            common = "un y de a en que se no es para con su del al lo como más este esa hacer tener puede"
        )
        add(
            SpokenLanguage.ITALIAN,
            strong = "il lo gli una è perché grazie ciao italiano molto anche come quando dove chi però sono essere avere",
            common = "un e di a in che non per con del al si da questo questa più fare puoi sei mi ti ci"
        )
        add(
            SpokenLanguage.PORTUGUESE,
            strong = "você não é português obrigado olá muito também como quando onde quem porque então já",
            common = "o a os as um uma e de em que para com do da no na por mais ser ter fazer pode isso"
        )
        add(
            SpokenLanguage.POLISH,
            strong = "jest nie to się że dlaczego dziękuję cześć polski bardzo ale także jak kiedy gdzie kto ponieważ",
            common = "i w na z do o od za przez jak co tak może być mieć tego tym już tylko"
        )
        add(
            SpokenLanguage.SWEDISH,
            strong = "är inte jag du vi det här hur varför tack hej svenska mycket men också när var vem",
            common = "och att en ett som på med för av till den de har kan ska vara göra mer bara"
        )
        add(
            SpokenLanguage.DANISH,
            strong = "er ikke jeg du vi det hvordan hvorfor tak hej dansk meget men også hvornår hvor hvem",
            common = "og at en et som på med for af til den de har kan skal være gøre mere bare"
        )
        add(
            SpokenLanguage.NORWEGIAN,
            strong = "er ikke jeg du vi det hvordan hvorfor takk hei norsk mye men også når hvor hvem",
            common = "og at en et som på med for av til den de har kan skal være gjøre mer bare"
        )
        add(
            SpokenLanguage.FINNISH,
            strong = "on ei minä sinä me se että miksi kiitos hei suomi paljon mutta myös kuinka milloin missä kuka",
            common = "ja en tai kun niin voi olla tehdä sitä tämä nyt vain jos"
        )
        add(
            SpokenLanguage.CZECH,
            strong = "je není já ty my to že proč děkuji ahoj čeština velmi ale také jak kdy kde kdo protože",
            common = "a v na s do o od za jako co tak může být mít toho tím už jen"
        )
        add(
            SpokenLanguage.SLOVAK,
            strong = "je nie ja ty my to že prečo ďakujem ahoj slovenčina veľmi ale aj ako kedy kde kto pretože",
            common = "a v na s do o od za ako čo tak môže byť mať toho tým už len"
        )
        add(
            SpokenLanguage.HUNGARIAN,
            strong = "van nem én te mi az hogy miért köszönöm szia magyar nagyon de is hogyan mikor hol ki mert",
            common = "a és egy be ki le fel meg már csak lehet lenni ezt azt itt ott"
        )
        add(
            SpokenLanguage.ROMANIAN,
            strong = "este nu eu tu noi asta că de ce mulțumesc salut română foarte dar și cum când unde cine pentru că",
            common = "un o și în la cu pe din care sunt are poate face mai doar acum aici"
        )
        add(
            SpokenLanguage.TURKISH,
            strong = "bir değil ben sen biz bu ki neden teşekkür merhaba türkçe çok ama da nasıl ne zaman nerede kim çünkü",
            common = "ve ile için gibi olarak var yok olabilir yapmak daha sadece şimdi burada"
        )
        add(
            SpokenLanguage.INDONESIAN,
            strong = "tidak saya kamu kita itu yang mengapa terima kasih halo bahasa sangat tetapi juga bagaimana kapan dimana siapa karena",
            common = "dan di ke dari untuk dengan pada ini ada bisa akan lebih hanya sekarang"
        )
        add(
            SpokenLanguage.VIETNAMESE,
            strong = "không tôi bạn chúng đó là tại sao cảm ơn xin chào tiếng việt rất nhưng cũng làm sao khi nào ở đâu ai",
            common = "và của cho với trên này có thể sẽ hơn chỉ bây giờ"
        )
    }
}
