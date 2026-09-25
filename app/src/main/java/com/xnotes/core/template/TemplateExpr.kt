package com.xnotes.core.template

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Thrown when an expression cannot be evaluated; the node holding it is skipped (SPEC §3). */
class TemplateEvalException(message: String) : RuntimeException(message) {
    override fun fillInStackTrace(): Throwable = this
}

/** What `%` refers to for the property an expression sits in (SPEC §2). */
enum class Axis { X, Y, SHORT, PLAIN }

/** Names and the current box an expression is evaluated against. */
interface ExprEnv {
    val boxW: Double
    val boxH: Double
    fun lookup(name: String): Double?
}

/** A parsed template expression (SPEC §3). Values are plain numbers; lengths are millimetres. */
sealed class Expr {
    abstract fun eval(env: ExprEnv, axis: Axis): Double

    /** The value when it depends on nothing, else null. */
    open val constant: Double? get() = null

    class Num(val v: Double) : Expr() {
        override fun eval(env: ExprEnv, axis: Axis) = v
        override val constant: Double get() = v
    }

    class Pct(val v: Double) : Expr() {
        override fun eval(env: ExprEnv, axis: Axis): Double = v / 100.0 * when (axis) {
            Axis.X -> env.boxW
            Axis.Y -> env.boxH
            Axis.SHORT -> min(env.boxW, env.boxH)
            Axis.PLAIN -> 1.0
        }
    }

    class Name(val name: String) : Expr() {
        override fun eval(env: ExprEnv, axis: Axis): Double = when (name) {
            "W" -> env.boxW
            "H" -> env.boxH
            else -> env.lookup(name) ?: if (name == "pi") Math.PI else throw TemplateEvalException("unknown name $name")
        }
    }

    class Neg(val e: Expr) : Expr() {
        override fun eval(env: ExprEnv, axis: Axis) = -e.eval(env, axis)
    }

    class Bin(val op: Char, val a: Expr, val b: Expr) : Expr() {
        override fun eval(env: ExprEnv, axis: Axis): Double {
            val x = a.eval(env, axis)
            val y = b.eval(env, axis)
            return when (op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                else -> if (y == 0.0) throw TemplateEvalException("division by zero") else x / y
            }
        }
    }

    class Call(val fn: String, val args: List<Expr>) : Expr() {
        override fun eval(env: ExprEnv, axis: Axis): Double {
            val v = DoubleArray(args.size) { args[it].eval(env, axis) }
            return apply(fn, v)
        }
    }

    /** A value that failed to parse: evaluating it always fails, so its node is skipped. */
    class Invalid(val reason: String) : Expr() {
        override fun eval(env: ExprEnv, axis: Axis): Double = throw TemplateEvalException(reason)
    }

    companion object {
        val ZERO = Num(0.0)
        val FULL = Pct(100.0)

        const val MAX_LENGTH = 1000
        private const val MAX_DEPTH = 64

        private val UNITS = mapOf("mm" to 1.0, "cm" to 10.0, "in" to 25.4, "pt" to 25.4 / 72.0, "deg" to 1.0)
        private val ARITY = mapOf(
            "abs" to 1, "floor" to 1, "ceil" to 1, "round" to 1, "sqrt" to 1,
            "sin" to 1, "cos" to 1, "tan" to 1, "log10" to 1, "mod" to 2, "pow" to 2,
        )
        val FUNCTIONS: Set<String> = ARITY.keys + setOf("min", "max")
        val BUILTIN_NAMES = setOf("W", "H", "i", "j", "pi")

        /** [text] parsed, or an [Invalid] explaining why not. Constant subtrees are folded. */
        fun parse(text: String): Expr {
            if (text.length > MAX_LENGTH) return Invalid("expression too long")
            return try {
                val p = Parser(text)
                val e = p.expr(0)
                p.end()
                e
            } catch (e: TemplateEvalException) {
                Invalid(e.message ?: "bad expression")
            }
        }

        /** A literal length: a number with an optional mm/cm/in/pt unit, or null. */
        fun literalLength(text: String): Double? {
            val m = LITERAL.matchEntire(text) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return v * (UNITS[m.groupValues[2]] ?: 1.0)
        }

        private val LITERAL = Regex("\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(mm|cm|in|pt)?\\s*")

        internal fun apply(fn: String, v: DoubleArray): Double {
            val r = when (fn) {
                "min" -> v.min()
                "max" -> v.max()
                "abs" -> abs(v[0])
                "floor" -> floor(v[0])
                "ceil" -> ceil(v[0])
                "round" -> if (v[0] < 0) -floor(-v[0] + 0.5) else floor(v[0] + 0.5)
                "mod" -> if (v[1] == 0.0) throw TemplateEvalException("mod by zero") else v[0] - v[1] * floor(v[0] / v[1])
                "sqrt" -> sqrt(v[0])
                "pow" -> v[0].pow(v[1])
                "sin" -> sin(Math.toRadians(v[0]))
                "cos" -> cos(Math.toRadians(v[0]))
                "tan" -> tan(Math.toRadians(v[0]))
                "log10" -> log10(v[0])
                else -> throw TemplateEvalException("unknown function $fn")
            }
            if (!r.isFinite()) throw TemplateEvalException("$fn gave no finite value")
            return r
        }

        private fun fold(e: Expr): Expr = try {
            when (e) {
                is Neg -> e.e.constant?.let { Num(-it) } ?: e
                is Bin -> if (e.a.constant != null && e.b.constant != null) Num(e.eval(NoEnv, Axis.PLAIN)) else e
                is Call -> if (e.args.all { it.constant != null }) Num(e.eval(NoEnv, Axis.PLAIN)) else e
                else -> e
            }
        } catch (_: TemplateEvalException) {
            e
        }

        private object NoEnv : ExprEnv {
            override val boxW = 0.0
            override val boxH = 0.0
            override fun lookup(name: String): Double? = null
        }

        private class Parser(val s: String) {
            var pos = 0

            fun end() {
                skipWs()
                if (pos < s.length) fail("unexpected '${s[pos]}'")
            }

            fun expr(depth: Int): Expr {
                if (depth > MAX_DEPTH) fail("expression nested too deeply")
                var left = product(depth)
                while (true) {
                    skipWs()
                    val c = s.getOrNull(pos) ?: return left
                    if (c != '+' && c != '-') return left
                    pos++
                    left = fold(Bin(c, left, product(depth)))
                }
            }

            fun product(depth: Int): Expr {
                var left = unary(depth)
                while (true) {
                    skipWs()
                    val c = s.getOrNull(pos) ?: return left
                    if (c != '*' && c != '/') return left
                    pos++
                    left = fold(Bin(c, left, unary(depth)))
                }
            }

            fun unary(depth: Int): Expr {
                skipWs()
                return when (s.getOrNull(pos)) {
                    '-' -> { pos++; fold(Neg(unary(depth + 1))) }
                    '+' -> { pos++; unary(depth + 1) }
                    else -> atom(depth)
                }
            }

            fun atom(depth: Int): Expr {
                skipWs()
                val c = s.getOrNull(pos) ?: fail("expression ends early")
                if (c == '(') {
                    pos++
                    val e = expr(depth + 1)
                    skipWs()
                    if (s.getOrNull(pos) != ')') fail("missing ')'")
                    pos++
                    return e
                }
                if (c.isDigit() || c == '.') return number()
                if (c.isLetter() || c == '_') {
                    val name = ident()
                    skipWs()
                    if (s.getOrNull(pos) != '(') return Name(name)
                    pos++
                    val args = ArrayList<Expr>()
                    skipWs()
                    if (s.getOrNull(pos) != ')') {
                        while (true) {
                            args.add(expr(depth + 1))
                            skipWs()
                            when (s.getOrNull(pos)) {
                                ',' -> pos++
                                ')' -> break
                                else -> fail("expected ',' or ')'")
                            }
                        }
                    }
                    pos++
                    val arity = ARITY[name]
                    when {
                        name == "min" || name == "max" -> if (args.isEmpty()) fail("$name needs arguments")
                        arity == null -> fail("unknown function $name")
                        args.size != arity -> fail("$name takes $arity argument(s)")
                    }
                    return fold(Call(name, args))
                }
                fail("unexpected '$c'")
            }

            fun number(): Expr {
                val start = pos
                while (pos < s.length && s[pos].isDigit()) pos++
                if (pos < s.length && s[pos] == '.') {
                    pos++
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                val v = s.substring(start, pos).toDoubleOrNull() ?: fail("bad number")
                if (s.getOrNull(pos) == '%') {
                    pos++
                    return Pct(v)
                }
                if (pos < s.length && s[pos].isLetter()) {
                    val unit = ident()
                    val f = UNITS[unit] ?: fail("unknown unit $unit")
                    return Num(v * f)
                }
                return Num(v)
            }

            fun ident(): String {
                val start = pos
                while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
                return s.substring(start, pos)
            }

            fun skipWs() {
                while (pos < s.length && s[pos].isWhitespace()) pos++
            }

            fun fail(msg: String): Nothing = throw TemplateEvalException(msg)
        }
    }
}
