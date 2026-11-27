package com.openminis.app.guard

import java.util.Locale

/**
 * C1: fully on-device scam/phishing heuristics for guard mode. No network
 * calls, no content leaves the device: the analyzer only scores text and
 * URLs locally and returns a verdict the UI renders in plain language.
 *
 * The threshold deliberately errs toward silence: a single weak signal
 * (e.g. a URL shortener, or an OTP code in an SMS) is common in legitimate
 * messages, so only a combination of signals - or one strong one such as a
 * brand lookalike domain - produces a warning.
 */
object ScamAnalyzer {

    enum class Reason {
        LOOKALIKE_DOMAIN,
        IP_HOST,
        SUSPICIOUS_URL,
        URL_SHORTENER,
        CREDENTIAL_REQUEST,
        URGENCY_PRESSURE,
        PRIZE_OR_MONEY,
    }

    data class Verdict(
        val score: Int,
        val reasons: List<Reason>,
        val urls: List<String>,
    ) {
        val isWarning: Boolean get() = score >= WARN_THRESHOLD
    }

    const val WARN_THRESHOLD = 40

    private val URL_REGEX = Regex("""(?i)\bhttps?://[^\s<>"')\]]+""")

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd",
        "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc", "buff.ly", "rebrand.ly",
    )

    private val SUSPICIOUS_TLDS = listOf(
        ".zip", ".mov", ".top", ".xyz", ".click", ".link", ".buzz",
        ".quest", ".cam", ".loan", ".win", ".rest", ".country",
    )

    /** brand token -> official domain suffix it must end with to be legitimate. */
    private val BRANDS = mapOf(
        "paypal" to "paypal.com",
        "vodafone" to "vodafone.com.eg",
        "etisalat" to "etisalat.eg",
        "orange" to "orange.eg",
        "whatsapp" to "whatsapp.com",
        "facebook" to "facebook.com",
        "instagram" to "instagram.com",
        "netflix" to "netflix.com",
        "amazon" to "amazon.com",
        "apple" to "apple.com",
        "microsoft" to "microsoft.com",
        "telegram" to "telegram.org",
        "aramex" to "aramex.com",
        "dhl" to "dhl.com",
        "cibeg" to "cibeg.com",
        "banquemisr" to "banquemisr.com",
        "nbe" to "nbe.com.eg",
    )

    private val CREDENTIAL_KEYWORDS = listOf(
        "verification code", "one-time password", "otp", "password", "cvv",
        "card number", "atm pin", "internet banking login",
        "رمز التحقق", "كود التحقق", "كلمة المرور", "كلمة السر",
        "رقم البطاقة", "الرقم السري", "بيانات حسابك البنكي",
    )

    private val URGENCY_KEYWORDS = listOf(
        "urgent", "immediately", "within 24 hours", "expires today",
        "account suspended", "account has been locked", "verify now",
        "عاجل", "فورًا", "فوراً", "فورا", "خلال ٢٤ ساعة", "خلال 24 ساعة",
        "تم إيقاف حسابك", "تم تعليق حسابك", "حسابك معلق", "تأكيد فوري",
    )

    private val PRIZE_KEYWORDS = listOf(
        "you won", "congratulations", "claim your prize", "cash reward",
        "lottery", "bitcoin", "crypto investment", "double your money",
        "فزت", "مبروك فزت", "جائزة", "جايزة", "اربح", "ربحت",
        "حوالة مالية", "استثمار مضمون", "ضاعف أموالك",
    )

    fun analyze(title: String?, text: String?): Verdict {
        val haystack = (listOfNotNull(title, text).joinToString("\n")).lowercase(Locale.US)
        var score = 0
        val reasons = linkedSetOf<Reason>()
        val urls = URL_REGEX.findAll(haystack).map { it.value }.toList()

        for (url in urls) {
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
                .substringAfter('@').substringBefore(':').lowercase(Locale.US)
            if (host.isEmpty()) continue
            if ('@' in url.substringAfter("://").substringBefore('/')) {
                score += 20
                reasons += Reason.SUSPICIOUS_URL
            }
            if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
                score += 30
                reasons += Reason.IP_HOST
            }
            if (host.startsWith("xn--") || host.contains(".xn--")) {
                score += 30
                reasons += Reason.SUSPICIOUS_URL
            }
            if (SHORTENERS.any { host == it || host.endsWith(".$it") }) {
                score += 25
                reasons += Reason.URL_SHORTENER
            }
            if (SUSPICIOUS_TLDS.any { host.endsWith(it) }) {
                score += 25
                reasons += Reason.SUSPICIOUS_URL
            }
            for ((brand, official) in BRANDS) {
                if (host.contains(brand) && !host.endsWith(official)) {
                    score += 40
                    reasons += Reason.LOOKALIKE_DOMAIN
                }
            }
        }

        if (CREDENTIAL_KEYWORDS.any { it in haystack }) {
            score += 35
            reasons += Reason.CREDENTIAL_REQUEST
        }
        if (URGENCY_KEYWORDS.any { it in haystack }) {
            score += 20
            reasons += Reason.URGENCY_PRESSURE
        }
        if (PRIZE_KEYWORDS.any { it in haystack }) {
            score += 20
            reasons += Reason.PRIZE_OR_MONEY
        }

        return Verdict(score, reasons.toList(), urls)
    }
}
