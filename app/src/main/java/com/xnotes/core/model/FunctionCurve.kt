package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.pow

/** The function a [com.xnotes.core.tools.ShapeKind.FUNCTION] shape plots: y = [expr] for x in [from, to]. */
data class FunctionSpec(val expr: String, val from: Double, val to: Double) {

    /**
     * The curve sampled across the domain and fitted to the unit box (y down), or null when the
     * expression does not parse or has no real value anywhere in the domain.
     */
    fun normalizedSamples(): List<Pt>? {
        val f = FunctionCurve.parse(expr) ?: return null
        if (!(to > from)) return null
        val xs = (0..SAMPLES).map { from + (to - from) * it / SAMPLES }
        val pts = xs.mapIndexedNotNull { i, x -> f(x).takeIf { it.isFinite() }?.let { i.toDouble() / SAMPLES to it } }
        if (pts.size < 2) return null
        var lo = pts.minOf { it.second }
        var hi = pts.maxOf { it.second }
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
        return pts.map { (nx, y) -> Pt(nx, (hi - y) / (hi - lo)) }
    }

    companion object {
        const val SAMPLES = 200

        /** The presets on the shape tool's second page. */
        val PRESETS = listOf(
            FunctionSpec("x^2", -2.0, 2.0),
            FunctionSpec("x^(1/2)", 0.0, 4.0),
            FunctionSpec("ln(x)", 0.1, 6.0),
            FunctionSpec("e^x", -2.0, 2.0),
            FunctionSpec("sin(x)", -2 * PI, 2 * PI),
            FunctionSpec("cos(x)", -2 * PI, 2 * PI),
        )

        fun preset(expr: String): FunctionSpec = PRESETS.firstOrNull { it.expr == expr } ?: PRESETS.first()
    }
}

/**
 * A small parser for the plotted expressions: + - * / ^, parentheses, implicit multiplication
 * (2x, 3sin(x), x(x+1)), the constants pi and e, and the usual functions. A function name may
 * take its argument without parentheses ("sin2x" is sin(2x)).
 */
object FunctionCurve {

    fun parse(expr: String): ((Double) -> Double)? = runCatching {
        val p = Parser(expr.lowercase().replace(" ", "").replace("**", "^"))
        val f = p.sum()
        if (!p.done()) null else f
    }.getOrNull()

    /** A constant expression such as "2pi" or "-1/2", or null. */
    fun constant(expr: String): Double? = parse(expr)?.invoke(0.0)?.takeIf { it.isFinite() }

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "arcsin" to { x -> kotlin.math.asin(x) },
        "arccos" to { x -> kotlin.math.acos(x) },
        "arctan" to { x -> kotlin.math.atan(x) },
        "sinh" to { x -> kotlin.math.sinh(x) },
        "cosh" to { x -> kotlin.math.cosh(x) },
        "tanh" to { x -> kotlin.math.tanh(x) },
        "sqrt" to { x -> kotlin.math.sqrt(x) },
        "sin" to { x -> kotlin.math.sin(x) },
        "cos" to { x -> kotlin.math.cos(x) },
        "tan" to { x -> kotlin.math.tan(x) },
        "cot" to { x -> 1.0 / kotlin.math.tan(x) },
        "exp" to { x -> kotlin.math.exp(x) },
        "log" to { x -> kotlin.math.log10(x) },
        "abs" to { x -> abs(x) },
        "ln" to { x -> kotlin.math.ln(x) },
    )

    private class Parser(val s: String) {
        var i = 0

        fun done() = i >= s.length

        private fun peek(): Char? = s.getOrNull(i)

        fun sum(): (Double) -> Double {
            var left = product()
            while (true) {
                val op = peek()
                if (op != '+' && op != '-') return left
                i++
                val l = left
                val r = product()
                left = if (op == '+') { x -> l(x) + r(x) } else { x -> l(x) - r(x) }
            }
        }

        private fun product(): (Double) -> Double {
            var left = unary()
            while (true) {
                val op = peek() ?: return left
                val l = left
                left = when {
                    op == '*' || op == '/' -> {
                        i++
                        val r = unary()
                        if (op == '*') { x: Double -> l(x) * r(x) } else { x: Double -> l(x) / r(x) }
                    }
                    startsPrimary(op) -> power().let { r -> { x: Double -> l(x) * r(x) } }
                    else -> return left
                }
            }
        }

        private fun unary(): (Double) -> Double {
            if (peek() == '-') {
                i++
                val v = unary()
                return { x -> -v(x) }
            }
            if (peek() == '+') i++
            return power()
        }

        private fun power(): (Double) -> Double {
            val base = primary()
            if (peek() != '^') return base
            i++
            val exp = unary()
            return { x -> pow(base(x), exp(x)) }
        }

        private fun startsPrimary(c: Char) = c.isLetterOrDigit() || c == '.' || c == '('

        private fun primary(): (Double) -> Double {
            val c = peek() ?: error("end")
            if (c == '(') {
                i++
                val v = sum()
                if (peek() != ')') error("paren")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (peek()?.let { it.isDigit() || it == '.' } == true) i++
                val v = s.substring(start, i).toDouble()
                return { v }
            }
            FUNCTIONS.entries.firstOrNull { s.startsWith(it.key, i) }?.let { (name, fn) ->
                i += name.length
                // Without parentheses the argument is one tight term, so "sin2x+1" is sin(2x) + 1.
                val arg = if (peek() == '(') primary() else tightProduct()
                return { x -> fn(arg(x)) }
            }
            if (s.startsWith("pi", i)) {
                i += 2
                return { PI }
            }
            i++
            return when (c) {
                'x' -> { x -> x }
                'e' -> { _ -> E }
                else -> error("symbol")
            }
        }

        private fun tightProduct(): (Double) -> Double {
            var left = power()
            while (peek()?.let { it.isLetterOrDigit() || it == '.' } == true) {
                val l = left
                val r = power()
                left = { x -> l(x) * r(x) }
            }
            return left
        }

        /** Real powers of negative bases with odd-denominator exponents (x^(1/3)) stay real. */
        private fun pow(b: Double, e: Double): Double {
            if (b >= 0.0 || e == kotlin.math.floor(e)) return b.pow(e)
            val inv = 1.0 / e
            val n = kotlin.math.round(inv)
            if (abs(inv - n) < 1e-9 && n.toLong() % 2L != 0L) return -((-b).pow(e))
            return Double.NaN
        }
    }
}
