package com.freepay.pos.utils

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

object CurrencyFormatter {
    private val formatter = NumberFormat.getCurrencyInstance(Locale.US)
    
    fun format(amount: BigDecimal): String {
        return formatter.format(amount)
    }
    
    fun parse(amountString: String): BigDecimal? {
        return try {
            val cleanString = amountString.replace(Regex("[^0-9.]"), "")
            if (cleanString.isNotEmpty()) {
                BigDecimal(cleanString)
            } else {
                null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}