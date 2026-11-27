package com.openminis.app.finance

import java.util.Locale

/**
 * C10: on-device parser that extracts a money movement (amount, currency,
 * direction, merchant) from a bank/wallet/merchant notification's text.
 * Arabic-Indic digits are normalized first; English and Arabic phrasing
 * common to Egyptian banks and wallets (CIB, NBE, Vodafone Cash, InstaPay,
 * valU, ...) are both recognized.
 *
 * Conservative by design: a notification is recorded only when BOTH an
 * amount-with-currency and a direction keyword are present, so ordinary
 * messages with numbers never become "expenses".
 */
object ExpenseParser {

    enum class Direction { DEBIT, CREDIT }

    data class Parsed(
        val amount: Double,
        val currency: String,
        val direction: Direction,
        val merchant: String?,
    )

    private val CURRENCY_TOKENS = mapOf(
        "egp" to "EGP", "جنيه" to "EGP", "ج.م" to "EGP", "جنية" to "EGP", "l.e" to "EGP", "le" to "EGP",
        "usd" to "USD", "$" to "USD", "دولار" to "USD",
        "eur" to "EUR", "€" to "EUR", "يورو" to "EUR",
        "sar" to "SAR", "ريال" to "SAR",
        "aed" to "AED", "درهم" to "AED",
        "gbp" to "GBP", "£" to "GBP",
        "kwd" to "KWD", "دينار" to "KWD",
    )

    private val DEBIT_KEYWORDS = listOf(
        "تم خصم", "خصم", "تم سحب", "سحب نقدي", "تم الدفع", "دفعت", "تحويل إلى", "تحويل الى",
        "تم تحويل", "مدفوعات", "شراء", "تم شحن", "دفع فاتورة",
        "debit", "debited", "purchase", "paid", "payment of", "withdrawal", "withdrawn",
        "charged", "spent", "sent to", "transfer to",
    )

    private val CREDIT_KEYWORDS = listOf(
        "تم إيداع", "تم ايداع", "إيداع", "ايداع", "استلمت", "تم استلام", "أضيف إلى حسابك",
        "حوالة واردة", "تم شحن رصيدك بمبلغ",
        "credited", "deposit", "deposited", "received", "refund", "cashback", "salary",
    )

    private val MERCHANT_PREFIXES = listOf(
        " at ", " لدى ", " من ", " إلى ", " الى ", " to ", " from ", " via ", " عبر ",
    )

    /** Normalize Arabic-Indic and Eastern Arabic digits to ASCII. */
    private fun normalizeDigits(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) {
            b.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                }
            )
        }
        return b.toString()
    }

    fun parse(title: String?, text: String?): Parsed? {
        val raw = listOfNotNull(title, text).joinToString("\n")
        if (raw.isBlank()) return null
        val normalized = normalizeDigits(raw)
        val lower = normalized.lowercase(Locale.US)

        // Direction first: no direction keyword, no record.
        val direction = when {
            DEBIT_KEYWORDS.any { it in lower } -> Direction.DEBIT
            CREDIT_KEYWORDS.any { it in lower } -> Direction.CREDIT
            else -> return null
        }

        // Amount near a currency token: "EGP 1,234.56", "1,234.56 جنيه", "$50".
        val amountRegex = Regex("""(\d{1,3}(?:[,\s]\d{3})+|\d+)(?:\.(\d{1,2}))?""")
        var best: Parsed? = null
        for (m in amountRegex.findAll(normalized)) {
            val intPart = m.groupValues[1].replace(Regex("[,\\s]"), "")
            val frac = m.groupValues[2]
            val amount = intPart.toDoubleOrNull()?.plus(
                if (frac.isEmpty()) 0.0 else ("0.$frac").toDouble()
            ) ?: continue
            if (amount <= 0.0 || amount > 100_000_000.0) continue
            val windowStart = maxOf(0, m.range.first - 12)
            val windowEnd = minOf(normalized.length, m.range.last + 12)
            val window = normalized.substring(windowStart, windowEnd).lowercase(Locale.US)
            val currency = CURRENCY_TOKENS.entries.firstOrNull { (token, _) ->
                Regex("""(^|[^\p{L}])""" + Regex.escape(token) + """([^\p{L}]|$)""").containsMatchIn(window)
            }?.value ?: continue
            val parsed = Parsed(amount, currency, direction, extractMerchant(normalized))
            // Prefer the largest amount that sits next to a currency token:
            // that is the transaction amount rather than a balance or limit.
            if (best == null || parsed.amount > best!!.amount) best = parsed
        }
        return best
    }

    private fun extractMerchant(text: String): String? {
        val lower = text.lowercase(Locale.US)
        for (prefix in MERCHANT_PREFIXES) {
            val idx = lower.indexOf(prefix)
            if (idx < 0) continue
            val start = idx + prefix.length
            val candidate = text.substring(start)
                .substringBefore('\n')
                .substringBefore('.')
                .take(40)
                .trim()
            if (candidate.length >= 2) return candidate
        }
        return null
    }
}
