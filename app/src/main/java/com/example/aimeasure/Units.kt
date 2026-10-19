package com.example.aimeasure

import java.util.Locale

object Units {
    fun num(meters: Float, inches: Boolean): String =
        String.format(Locale.US, "%.1f", if (inches) meters / 0.0254f else meters * 100f)
    fun unit(inches: Boolean) = if (inches) "in" else "cm"
    fun fmt(meters: Float, inches: Boolean) = num(meters, inches) + " " + unit(inches)
}
