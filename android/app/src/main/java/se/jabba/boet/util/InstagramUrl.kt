package se.jabba.boet.util

// Recognizes Instagram Reel links so the "Parse URL" screen and the share
// target (MainActivity/BoetNavHost) can both route them into the Instagram
// import pipeline (Repository.startUrlScrape) instead of the generic
// website scraper.
object InstagramUrl {
    // https://www.instagram.com/reel/<id>/... , .../reels/<id>/... ,
    // .../share/reel/<token>/... — with or without "www."/"m.", http or https
    // (share text sometimes drops the scheme or "www").
    private val REEL_PATTERN = Regex(
        """https?://(www\.|m\.)?instagram\.com/(reel|reels|share/reel)/\S+""",
        RegexOption.IGNORE_CASE,
    )

    fun isReelUrl(url: String): Boolean = REEL_PATTERN.containsMatchIn(url.trim())

    // Pulls the first Instagram Reel URL out of arbitrary shared text (the
    // Instagram share sheet sends a blurb + the link; trailing prose
    // punctuation right after the URL is trimmed since the regex's greedy
    // \S+ can otherwise swallow it).
    fun extractReelUrl(text: String): String? =
        REEL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
}
