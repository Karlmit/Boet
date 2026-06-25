package se.jabba.boet.ai

// On-device grocery categorization knowledge base — a Kotlin port of the server's
// categorize.js. This is the deterministic baseline that runs instantly and fully
// offline. Priority order at the call site (see CategoryEngine):
//   learned household mapping  ->  this keyword KB  ->  on-device LLM  ->  Övrigt
//
// "Confident" here means a real keyword hit; falling through to Övrigt is *not*
// confident, which is the signal that the on-device LLM should take a look.
object Categorizer {

    const val OTHER = "Övrigt"

    val DEFAULT_CATEGORIES = listOf(
        "Frukt & grönt",
        "Bröd",
        "Mejeri",
        "Kött & fisk",
        "Frys",
        "Torrvaror",
        "Snacks",
        "Hushåll",
        "Övrigt",
    )

    // keyword (substring, lowercased) -> category name. Mirrors categorize.js.
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "Frukt & grönt" to listOf(
            "banan", "äpple", "apple", "päron", "apelsin", "orange", "citron", "lime",
            "tomat", "gurka", "sallad", "sallat", "lök", "vitlök", "morot", "morötter",
            "potatis", "paprika", "avokado", "avocado", "broccoli", "blomkål", "spenat",
            "champinjon", "svamp", "zucchini", "aubergin", "ingefära", "chili",
            "druvor", "vindruvor", "jordgubb", "blåbär", "hallon", "melon", "mango",
            "persika", "nektarin", "kiwi", "ananas", "purjo", "rödlök", "palsternacka",
            "rödbeta", "kål", "grönsaker", "frukt", "örter", "basilika", "persilja", "dill",
        ),
        "Bröd" to listOf(
            "bröd", "bread", "baguette", "fralla", "frallor", "limpa", "rågbröd",
            "knäckebröd", "tunnbröd", "pitabröd", "tortilla", "bulle", "bullar",
            "croissant", "kanelbulle", "toast", "hamburgerbröd", "korvbröd", "frukostbröd",
        ),
        "Mejeri" to listOf(
            "mjölk", "milk", "filmjölk", "filbunke", "yoghurt", "yogurt", "kvarg", "grädde",
            "crème fraiche", "creme fraiche", "gräddfil", "smör", "butter", "margarin",
            "ost", "cheese", "halloumi", "fetaost", "mozzarella", "parmesan", "keso",
            "ägg", "egg", "cottage", "kefir", "havremjölk", "oatly", "sojamjölk",
            "laktosfri", "cheddar", "philadelphia", "färskost",
        ),
        "Kött & fisk" to listOf(
            "kyckling", "chicken", "kött", "köttfärs", "färs", "nötkött", "fläsk",
            "bacon", "korv", "sausage", "skinka", "ham", "salami", "biff", "steak",
            "fisk", "fish", "lax", "salmon", "torsk", "räkor", "shrimp", "tonfisk",
            "tuna", "fiskpinnar", "pannbiff", "leverpastej", "pålägg", "fläskfilé",
            "kotlett", "revben", "kalkon", "turkey", "kassler", "falukorv", "prinskorv",
        ),
        "Frys" to listOf(
            "frys", "frozen", "glass", "ice cream", "pizza", "frysta", "fryst",
            "pommes", "pommes frites", "ärtor", "köttbullar", "lövbiff", "våfflor",
        ),
        "Torrvaror" to listOf(
            "pasta", "spaghetti", "makaroner", "ris", "rice", "mjöl", "flour", "socker",
            "sugar", "salt", "peppar", "kryddor", "olja", "oil", "olivolja", "vinäger",
            "ketchup", "senap", "majonnäs", "mayo", "bönor", "linser", "kikärtor",
            "krossade tomater", "passata", "buljong", "havregryn", "müsli", "cornflakes",
            "flingor", "kaffe", "coffee", "te", "tea", "kakao", "honung", "sylt",
            "nötter", "mandel", "russin", "jäst", "bakpulver", "vaniljsocker", "couscous",
            "bulgur", "quinoa", "nudlar", "noodles", "soja", "sojasås", "curry", "tacokrydda",
            "tacoskal", "pesto", "oliver", "tahini", "kokosmjölk", "sirap",
        ),
        "Snacks" to listOf(
            "chips", "godis", "candy", "choklad", "chocolate", "kex", "kakor", "cookies",
            "popcorn", "läsk", "soda", "cola", "saft", "juice",
            "energidryck", "snacks", "ostbågar", "lakrits", "tuggummi", "glasspinne",
            "bubbel", "festis", "dipp",
        ),
        "Hushåll" to listOf(
            "toalettpapper", "toapapper", "hushållspapper", "papper", "disktrasa",
            "diskmedel", "tvättmedel", "sköljmedel", "tvål", "soap", "schampo", "shampoo",
            "balsam", "tandkräm", "tandborste", "deodorant", "rengöring", "allrent",
            "soppåsar", "fryspåsar", "plastfolie", "aluminiumfolie", "bakplåtspapper",
            "servetter", "blöjor", "bindor", "tamponger", "batterier", "glödlampa",
            "ljus", "tändstickor", "kattmat", "hundmat", "rakblad", "bomull", "städ",
        ),
    )

    // Normalize an item name into a stable lookup key (lowercase, no quantity noise).
    // Mirrors normalizeKey in categorize.js so learned keys match across app/server.
    fun normalizeKey(name: String?): String {
        return (name ?: "")
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\b\\d+([.,]\\d+)?\\s*(kg|g|l|dl|cl|ml|st|pack|paket|x)?\\b"), "")
            .replace(Regex("\\bx\\s*\\d+\\b"), "")
            .trim()
    }

    // Best-guess category name from the keyword KB, or null if nothing matched
    // (the caller treats null as "uncertain → eligible for the on-device LLM").
    fun keywordGuess(name: String): String? {
        val key = normalizeKey(name)
        if (key.isEmpty()) return null
        for ((category, words) in KEYWORDS) {
            for (w in words) {
                if (key.contains(w)) return category
            }
        }
        return null
    }
}
