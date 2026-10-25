package com.mediaviewer.domain

/** Natural, case-insensitive name order: `img2` < `img10` (README §4 "Item order"). */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Compare digit runs by value: skip leading zeros, then length, then digits.
                var si = i
                var sj = j
                while (si < a.length && a[si] == '0') si++
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                var ej = sj
                while (ei < a.length && a[ei].isDigit()) ei++
                while (ej < b.length && b[ej].isDigit()) ej++
                val lenA = ei - si
                val lenB = ej - sj
                if (lenA != lenB) return lenA - lenB
                for (k in 0 until lenA) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                // Same value: fewer leading zeros first, so the order stays total.
                val zeros = (si - i) - (sj - j)
                if (zeros != 0) return zeros
                i = ei
                j = ej
            } else {
                if (ca != cb) {
                    val la = ca.lowercaseChar()
                    val lb = cb.lowercaseChar()
                    if (la != lb) return la - lb
                }
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}
