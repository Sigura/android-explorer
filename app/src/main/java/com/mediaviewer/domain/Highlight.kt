package com.mediaviewer.domain

/** What a coloured stretch of text is; the editor maps each role to a style (todo §6.5). */
enum class Role { KEYWORD, STRING, COMMENT, NUMBER, TYPE, META, HEADING, STRONG, EMPHASIS, ERROR, WARN, ADDED }

/** Syntax colouring families, chosen from the file name. */
enum class Language(val label: String) {
    PLAIN("Plain text"),
    C("C"), CPP("C++"), OBJC("Objective-C"), CSHARP("C#"), JAVA("Java"), KOTLIN("Kotlin"), SCALA("Scala"),
    GROOVY("Groovy"), GO("Go"), RUST("Rust"), SWIFT("Swift"), DART("Dart"), JS("JavaScript"), TS("TypeScript"),
    PHP("PHP"), PYTHON("Python"), RUBY("Ruby"), PERL("Perl"), LUA("Lua"), R("R"), SHELL("Shell"), BATCH("Batch"),
    POWERSHELL("PowerShell"), MAKE("Makefile"), DOCKER("Dockerfile"), ASM("Assembly"), SQL("SQL"),
    HTML("HTML"), XML("XML"), JSON("JSON"), CSS("CSS"), YAML("YAML"), INI("Config"), TOML("TOML"),
    MARKDOWN("Markdown"), SUBTITLES("Subtitles"), LOG("Log"), DIFF("Diff"), CSV("CSV"), TSV("TSV"),
}

/**
 * Coloured ranges of a text, sorted by start, not overlapping. Kept as plain arrays: a 200 KB file
 * can have tens of thousands of them.
 */
class Spans internal constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val roles: ByteArray,
    val size: Int,
) {
    fun start(i: Int) = starts[i]
    fun end(i: Int) = ends[i]
    fun role(i: Int) = Role.entries[roles[i].toInt()]

    /**
     * The spans moved for an edit, so colours stay on their text while typing until the next lexing
     * pass. Spans inside replaced text shrink or disappear.
     */
    fun shifted(c: TextChange): Spans {
        val delta = c.inserted - c.removed
        val editEnd = c.pos + c.removed
        // Text typed at a span's end stays outside it; text typed inside it (a string) extends it.
        fun mapStart(x: Int) = when {
            x < c.pos -> x
            x >= editEnd -> x + delta
            else -> c.pos + c.inserted
        }
        fun mapEnd(x: Int) = when {
            x <= c.pos -> x
            x >= editEnd -> x + delta
            else -> c.pos
        }
        val b = SpanBuilder()
        for (i in 0 until size) {
            val s = mapStart(starts[i])
            val e = mapEnd(ends[i])
            if (e > s) b.add(s, e, role(i))
        }
        return b.build()
    }

    override fun toString(): String = (0 until size).joinToString { "${role(it)}[${starts[it]},${ends[it]})" }

    companion object {
        val EMPTY = Spans(IntArray(0), IntArray(0), ByteArray(0), 0)
    }
}

class SpanBuilder {
    private var starts = IntArray(64)
    private var ends = IntArray(64)
    private var roles = ByteArray(64)
    private var n = 0

    fun add(start: Int, end: Int, role: Role) {
        if (end <= start) return
        if (n == starts.size) {
            starts = starts.copyOf(n * 2)
            ends = ends.copyOf(n * 2)
            roles = roles.copyOf(n * 2)
        }
        starts[n] = start
        ends[n] = end
        roles[n] = role.ordinal.toByte()
        n++
    }

    fun build() = Spans(starts, ends, roles, n)
}

/**
 * Small hand-written lexers, one per family (todo §6.5). They only colour: no parsing, never an
 * error, linear time. Pure Kotlin, unit-tested.
 */
object Highlight {
    fun language(name: String): Language {
        val lower = name.lowercase()
        BY_NAME[lower]?.let { return it }
        val dot = lower.lastIndexOf('.')
        if (dot < 0 || dot == lower.length - 1) return Language.PLAIN
        return BY_EXT[lower.substring(dot + 1)] ?: if (dot == 0) Language.INI else Language.PLAIN
    }

    private val BY_NAME = mapOf(
        "makefile" to Language.MAKE, "gnumakefile" to Language.MAKE, "dockerfile" to Language.DOCKER,
        "jenkinsfile" to Language.GROOVY, ".bashrc" to Language.SHELL, ".profile" to Language.SHELL,
        ".zshrc" to Language.SHELL, ".bash_profile" to Language.SHELL,
    )

    private val BY_EXT: Map<String, Language> = buildMap {
        fun put(lang: Language, vararg ext: String) = ext.forEach { put(it, lang) }
        put(Language.C, "c", "h")
        put(Language.CPP, "cc", "cpp", "cxx", "hpp", "hh", "hxx", "ino")
        put(Language.OBJC, "m", "mm")
        put(Language.CSHARP, "cs")
        put(Language.JAVA, "java")
        put(Language.KOTLIN, "kt", "kts")
        put(Language.SCALA, "scala", "sc")
        put(Language.GROOVY, "groovy", "gradle")
        put(Language.GO, "go")
        put(Language.RUST, "rs")
        put(Language.SWIFT, "swift")
        put(Language.DART, "dart")
        put(Language.JS, "js", "mjs", "cjs", "jsx")
        put(Language.TS, "ts", "tsx", "mts", "cts")
        put(Language.PHP, "php")
        put(Language.PYTHON, "py", "pyw", "pyi")
        put(Language.RUBY, "rb", "rake", "gemspec")
        put(Language.PERL, "pl", "pm")
        put(Language.LUA, "lua")
        put(Language.R, "r")
        put(Language.SHELL, "sh", "bash", "zsh", "fish", "ksh")
        put(Language.BATCH, "bat", "cmd")
        put(Language.POWERSHELL, "ps1", "psm1")
        put(Language.MAKE, "mk", "make")
        put(Language.DOCKER, "dockerfile")
        put(Language.ASM, "asm", "s")
        put(Language.SQL, "sql")
        put(Language.HTML, "html", "htm", "xhtml", "vue", "svelte")
        put(Language.XML, "xml", "svg", "plist", "xsd", "xsl", "xslt", "rss", "fb2", "kml", "gpx")
        put(Language.JSON, "json", "jsonl", "ipynb", "geojson", "webmanifest")
        put(Language.CSS, "css", "scss", "less")
        put(Language.YAML, "yaml", "yml")
        put(Language.INI, "ini", "conf", "cfg", "properties", "env", "desktop", "editorconfig", "gitignore", "gitattributes")
        put(Language.TOML, "toml")
        put(Language.MARKDOWN, "md", "markdown")
        put(Language.SUBTITLES, "srt", "vtt", "ass", "ssa", "lrc")
        put(Language.LOG, "log")
        put(Language.DIFF, "diff", "patch")
        put(Language.CSV, "csv")
        put(Language.TSV, "tsv")
    }

    fun spans(text: CharSequence, lang: Language): Spans {
        val b = SpanBuilder()
        when (lang) {
            Language.PLAIN -> Unit
            Language.HTML, Language.XML -> markup(text, b)
            Language.JSON -> json(text, b)
            Language.CSS -> css(text, b)
            Language.YAML -> yaml(text, b)
            Language.INI -> keyValue(text, b, inlineComments = false)
            Language.TOML -> keyValue(text, b, inlineComments = true)
            Language.MARKDOWN -> markdown(text, b)
            Language.SUBTITLES -> subtitles(text, b)
            Language.LOG -> log(text, b)
            Language.DIFF -> diff(text, b)
            Language.CSV -> csv(text, b, null)
            Language.TSV -> csv(text, b, '\t')
            else -> code(text, SPECS.getValue(lang), b)
        }
        return b.build()
    }

    // ------------------------------------------------------------------ programming languages

    private class CodeSpec(
        keywords: String,
        val lineComments: List<String> = listOf("//"),
        val block: Pair<String, String>? = "/*" to "*/",
        val quotes: String = "\"'",
        /** Quote characters whose strings may span lines (JavaScript and Go backticks). */
        val multiline: String = "",
        /** `"""` strings. */
        val triple: Boolean = false,
        /** `'x'` is a character, never a string: Rust lifetimes and `it's` in comments don't open one. */
        val charQuote: Boolean = false,
        /** `@Name` annotations and decorators. */
        val annotations: Boolean = false,
        /** `#include` and other `#` lines. */
        val preprocessor: Boolean = false,
        /** `$name` and `${…}` variables. */
        val variables: Boolean = false,
        /** Batch `%name%` variables. */
        val percentVariables: Boolean = false,
        /** Shell `'…'` strings have no escapes. */
        val rawSingleQuotes: Boolean = false,
        val ignoreCase: Boolean = false,
        /** Capitalised words are types. */
        val types: Boolean = true,
    ) {
        val keywords: Set<String> = keywords.split(' ').filter { it.isNotEmpty() }.map { if (ignoreCase) it.lowercase() else it }.toSet()
    }

    private const val C_KW = "auto break case char const continue default do double else enum extern float for goto if inline int long " +
        "register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while NULL true false bool"
    private const val CPP_KW = "$C_KW class namespace template typename public private protected virtual override final new delete this " +
        "throw try catch using operator friend explicit constexpr nullptr noexcept static_cast dynamic_cast reinterpret_cast const_cast decltype mutable"
    private const val JAVA_KW = "abstract assert boolean break byte case catch char class const continue default do double else enum extends " +
        "final finally float for goto if implements import instanceof int interface long native new package private protected public return " +
        "short static strictfp super switch synchronized this throw throws transient try void volatile while true false null var record yield sealed permits"
    private const val JS_KW = "break case catch class const continue debugger default delete do else export extends false finally for " +
        "function if import in instanceof let new null return super switch this throw true try typeof var void while with yield async await of " +
        "static get set undefined"

    private val SPECS: Map<Language, CodeSpec> = mapOf(
        Language.C to CodeSpec(C_KW, charQuote = true, preprocessor = true),
        Language.CPP to CodeSpec(CPP_KW, charQuote = true, preprocessor = true),
        Language.OBJC to CodeSpec("$C_KW self nil YES NO id super", charQuote = true, preprocessor = true, annotations = true),
        Language.CSHARP to CodeSpec(
            "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum " +
                "event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace " +
                "new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc " +
                "static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while var " +
                "async await get set yield record",
            charQuote = true, preprocessor = true,
        ),
        Language.JAVA to CodeSpec(JAVA_KW, charQuote = true, annotations = true),
        Language.KOTLIN to CodeSpec(
            "as break class continue do else false for fun if in interface is null object package return super this throw true try " +
                "typealias typeof val var when while by catch constructor finally get import init set where actual abstract annotation " +
                "companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out " +
                "override private protected public reified sealed suspend tailrec vararg it value",
            triple = true, charQuote = true, annotations = true,
        ),
        Language.SCALA to CodeSpec(
            "abstract case catch class def do else extends false final finally for if implicit import lazy match new null object override " +
                "package private protected return sealed super this throw trait try true type val var while with yield given using enum then export",
            triple = true, charQuote = true, annotations = true,
        ),
        Language.GROOVY to CodeSpec("$JAVA_KW def as in trait it", triple = true, annotations = true),
        Language.GO to CodeSpec(
            "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return " +
                "select struct switch type var true false nil iota bool byte error float32 float64 int int8 int16 int32 int64 rune string " +
                "uint uint8 uint16 uint32 uint64 uintptr any",
            quotes = "\"'`", multiline = "`", charQuote = true,
        ),
        Language.RUST to CodeSpec(
            "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref " +
                "return self Self static struct super trait true type unsafe use where while",
            charQuote = true,
        ),
        Language.SWIFT to CodeSpec(
            "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public " +
                "rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat " +
                "return switch where while as catch false is nil super self Self throw throws true try async await",
            quotes = "\"", triple = true, annotations = true,
        ),
        Language.DART to CodeSpec(
            "abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export " +
                "extends extension external factory false final finally for get if implements import in interface is late library mixin new " +
                "null on operator part required rethrow return set show static super switch sync this throw true try typedef var void while with yield",
            triple = true, annotations = true,
        ),
        Language.JS to CodeSpec(JS_KW, quotes = "\"'`", multiline = "`", annotations = true),
        Language.TS to CodeSpec(
            "$JS_KW type interface enum implements private protected public readonly declare namespace abstract as any boolean number string " +
                "unknown never keyof module",
            quotes = "\"'`", multiline = "`", annotations = true,
        ),
        Language.PHP to CodeSpec(
            "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare " +
                "endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include " +
                "include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require " +
                "require_once return static switch throw trait try unset use var while xor yield true false null",
            lineComments = listOf("//", "#"), variables = true,
        ),
        Language.PYTHON to CodeSpec(
            "False None True and as assert async await break class continue def del elif else except finally for from global if import in " +
                "is lambda nonlocal not or pass raise return try while with yield self match case",
            lineComments = listOf("#"), block = null, triple = true, annotations = true,
        ),
        Language.RUBY to CodeSpec(
            "BEGIN END alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo " +
                "rescue retry return self super then true undef unless until when while yield require puts attr_accessor",
            lineComments = listOf("#"), block = "=begin" to "=end",
        ),
        Language.PERL to CodeSpec(
            "my our local sub if elsif else unless while until for foreach do last next redo return use package require print and or not eq ne lt gt le ge",
            lineComments = listOf("#"), block = null, variables = true,
        ),
        Language.LUA to CodeSpec(
            "and break do else elseif end false for function goto if in local nil not or repeat return then true until while",
            lineComments = listOf("--"), block = "--[[" to "]]",
        ),
        Language.R to CodeSpec(
            "if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA library return",
            lineComments = listOf("#"), block = null, types = false,
        ),
        Language.SHELL to CodeSpec(
            "if then else elif fi case esac for while until do done in function select return exit local export readonly declare unset " +
                "shift break continue echo source alias set trap eval exec cd test",
            lineComments = listOf("#"), block = null, quotes = "\"'`", multiline = "\"'`", variables = true, rawSingleQuotes = true, types = false,
        ),
        Language.BATCH to CodeSpec(
            "echo set if else for in do goto call exit not exist defined errorlevel setlocal endlocal shift pause",
            lineComments = listOf("::", "rem ", "@rem "), block = null, quotes = "\"", percentVariables = true, ignoreCase = true, types = false,
        ),
        Language.POWERSHELL to CodeSpec(
            "begin break catch class continue data define do dynamicparam else elseif end exit filter finally for foreach from function if " +
                "in param process return switch throw trap try until using var while",
            lineComments = listOf("#"), block = "<#" to "#>", variables = true, ignoreCase = true, types = false,
        ),
        Language.MAKE to CodeSpec(
            "ifeq ifneq ifdef ifndef else endif include define endef export override",
            lineComments = listOf("#"), block = null, variables = true, types = false,
        ),
        Language.DOCKER to CodeSpec(
            "from run cmd label expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell maintainer as",
            lineComments = listOf("#"), block = null, variables = true, ignoreCase = true, types = false,
        ),
        Language.ASM to CodeSpec("", lineComments = listOf(";", "#", "//"), types = false),
        Language.SQL to CodeSpec(
            "select from where insert into values update set delete create table drop alter add column index primary key foreign references " +
                "not null unique default and or in is like between join left right inner outer full cross on as group by order having limit " +
                "offset union all distinct case when then else end exists view trigger begin commit rollback transaction if int integer " +
                "varchar text real blob date asc desc count sum avg min max with returning",
            lineComments = listOf("--"), ignoreCase = true, types = false,
        ),
    )

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun lineEnd(t: CharSequence, i: Int): Int {
        var j = i
        while (j < t.length && t[j] != '\n') j++
        return j
    }

    private fun indexOf(t: CharSequence, s: String, from: Int): Int {
        if (t is String) return t.indexOf(s, from)
        var i = from
        while (i <= t.length - s.length) {
            if (t.regionMatches(i, s, 0, s.length)) return i
            i++
        }
        return -1
    }

    private fun CharSequence.regionMatches(at: Int, s: String, from: Int, len: Int, ignoreCase: Boolean = false): Boolean {
        if (at < 0 || at + len > length) return false
        for (k in 0 until len) {
            val a = this[at + k]
            val b = s[from + k]
            if (a != b && !(ignoreCase && a.lowercaseChar() == b.lowercaseChar())) return false
        }
        return true
    }

    private fun startsWith(t: CharSequence, s: String, at: Int, ignoreCase: Boolean = false) = t.regionMatches(at, s, 0, s.length, ignoreCase)

    /** End of a quoted string starting at [i]; stops at the line end unless [multi]. */
    private fun stringEnd(t: CharSequence, i: Int, multi: Boolean, escapes: Boolean = true): Int {
        val q = t[i]
        var j = i + 1
        while (j < t.length) {
            val c = t[j]
            if (c == '\\' && escapes) {
                j += 2
                continue
            }
            if (c == q) return j + 1
            if (c == '\n' && !multi) return j
            j++
        }
        return t.length
    }

    private fun numberEnd(t: CharSequence, i: Int): Int {
        var j = i + 1
        while (j < t.length) {
            val c = t[j]
            when {
                c.isLetterOrDigit() || c == '_' -> j++
                c == '.' && j + 1 < t.length && t[j + 1].isDigit() -> j++
                (c == '+' || c == '-') && (t[j - 1] == 'e' || t[j - 1] == 'E') && t[i] != '0' -> j++
                else -> break
            }
        }
        return j
    }

    private fun code(t: CharSequence, s: CodeSpec, out: SpanBuilder) {
        val n = t.length
        var i = 0
        var lineStart = true
        while (i < n) {
            val c = t[i]
            if (c == '\n') {
                lineStart = true
                i++
                continue
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++
                continue
            }
            val atLineStart = lineStart
            lineStart = false
            if (s.preprocessor && atLineStart && c == '#') {
                val e = lineEnd(t, i)
                out.add(i, e, Role.META)
                i = e
                continue
            }
            val block = s.block
            if (block != null && startsWith(t, block.first, i)) {
                val close = indexOf(t, block.second, i + block.first.length)
                val e = if (close < 0) n else close + block.second.length
                out.add(i, e, Role.COMMENT)
                i = e
                continue
            }
            val comment = s.lineComments.firstOrNull { startsWith(t, it, i, s.ignoreCase) }
            // In shells `#` starts a comment only after a space: `$#` and `a#b` aren't comments.
            if (comment != null && !(comment == "#" && s.variables && i > 0 && !t[i - 1].isWhitespace() && t[i - 1] != ';')) {
                val e = lineEnd(t, i)
                out.add(i, e, Role.COMMENT)
                i = e
                continue
            }
            if (c in s.quotes) {
                if (s.triple && startsWith(t, "$c$c$c", i)) {
                    val close = indexOf(t, "$c$c$c", i + 3)
                    val e = if (close < 0) n else close + 3
                    out.add(i, e, Role.STRING)
                    i = e
                    continue
                }
                val e = stringEnd(t, i, c in s.multiline, escapes = !(c == '\'' && s.rawSingleQuotes))
                // A character is one letter or an escape: 'x', '\n', '\u{1F600}'.
                val isChar = t[e - 1] == '\'' && (e - i == 3 || (e - i in 4..12 && t[i + 1] == '\\'))
                if (c == '\'' && s.charQuote && !isChar) {
                    i++ // a lifetime or an apostrophe, not a character
                    continue
                }
                out.add(i, e, Role.STRING)
                i = e
                continue
            }
            if (c.isDigit() && (i == 0 || !isIdent(t[i - 1]))) {
                val e = numberEnd(t, i)
                out.add(i, e, Role.NUMBER)
                i = e
                continue
            }
            if (isIdentStart(c)) {
                var e = i + 1
                while (e < n && isIdent(t[e])) e++
                val word = t.subSequence(i, e).toString()
                val key = if (s.ignoreCase) word.lowercase() else word
                when {
                    key in s.keywords -> out.add(i, e, Role.KEYWORD)
                    s.types && word.length > 1 && word[0].isUpperCase() -> out.add(i, e, Role.TYPE)
                }
                i = e
                continue
            }
            if (c == '@' && s.annotations && i + 1 < n && isIdentStart(t[i + 1])) {
                var e = i + 1
                while (e < n && (isIdent(t[e]) || t[e] == '.')) e++
                out.add(i, e, Role.META)
                i = e
                continue
            }
            if (((c == '$' && s.variables) || (c == '%' && s.percentVariables)) && i + 1 < n) {
                val next = t[i + 1]
                val e = when {
                    next == '{' || next == '(' -> {
                        val close = if (next == '{') '}' else ')'
                        var j = i + 2
                        while (j < n && t[j] != close && t[j] != '\n') j++
                        if (j < n && t[j] == close) j + 1 else j
                    }
                    isIdent(next) -> {
                        var j = i + 1
                        while (j < n && isIdent(t[j])) j++
                        // Batch: %name%
                        if (c == '%' && j < n && t[j] == '%') j + 1 else j
                    }
                    else -> i + 1
                }
                if (e > i + 1) out.add(i, e, Role.META)
                i = e
                continue
            }
            i++
        }
    }

    // ------------------------------------------------------------------ markup and data

    private fun markup(t: CharSequence, out: SpanBuilder) {
        val n = t.length
        var i = 0
        while (i < n) {
            val c = t[i]
            when {
                startsWith(t, "<!--", i) -> {
                    val close = indexOf(t, "-->", i + 4)
                    val e = if (close < 0) n else close + 3
                    out.add(i, e, Role.COMMENT)
                    i = e
                }
                startsWith(t, "<![CDATA[", i) -> {
                    val close = indexOf(t, "]]>", i + 9)
                    val e = if (close < 0) n else close + 3
                    out.add(i, e, Role.STRING)
                    i = e
                }
                c == '<' && i + 1 < n && (t[i + 1].isLetter() || t[i + 1] in "/?!") -> i = tag(t, i, out)
                c == '&' -> {
                    var j = i + 1
                    while (j < n && j - i < 12 && (t[j].isLetterOrDigit() || t[j] == '#')) j++
                    if (j < n && t[j] == ';' && j > i + 1) {
                        out.add(i, j + 1, Role.META)
                        i = j + 1
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
    }

    /** A tag from `<` to `>`: name, attribute names and values. Returns the index after it. */
    private fun tag(t: CharSequence, start: Int, out: SpanBuilder): Int {
        val n = t.length
        var j = start + 1
        if (t[j] in "/?!") j++
        while (j < n && (t[j].isLetterOrDigit() || t[j] in "-_:.")) j++
        out.add(start, j, Role.KEYWORD)
        while (j < n) {
            val c = t[j]
            when {
                c == '>' -> {
                    out.add(j, j + 1, Role.KEYWORD)
                    return j + 1
                }
                (c == '/' || c == '?') && j + 1 < n && t[j + 1] == '>' -> {
                    out.add(j, j + 2, Role.KEYWORD)
                    return j + 2
                }
                c == '"' || c == '\'' -> {
                    val e = stringEnd(t, j, multi = true, escapes = false)
                    out.add(j, e, Role.STRING)
                    j = e
                }
                c.isLetter() || c == '_' || c == ':' || c == '@' -> {
                    var e = j + 1
                    while (e < n && (t[e].isLetterOrDigit() || t[e] in "-_:.")) e++
                    out.add(j, e, Role.TYPE)
                    j = e
                }
                c == '<' -> return j // a broken tag: start over at the next one
                else -> j++
            }
        }
        return n
    }

    private fun json(t: CharSequence, out: SpanBuilder) {
        val n = t.length
        var i = 0
        while (i < n) {
            val c = t[i]
            when {
                c == '"' -> {
                    val e = stringEnd(t, i, multi = false)
                    var k = e
                    while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                    out.add(i, e, if (k < n && t[k] == ':') Role.TYPE else Role.STRING)
                    i = e
                }
                c == '-' || c.isDigit() -> {
                    val e = numberEnd(t, i)
                    out.add(i, e, Role.NUMBER)
                    i = e
                }
                c.isLetter() -> {
                    var e = i + 1
                    while (e < n && t[e].isLetter()) e++
                    val w = t.subSequence(i, e).toString()
                    if (w == "true" || w == "false" || w == "null") out.add(i, e, Role.KEYWORD)
                    i = e
                }
                else -> i++
            }
        }
    }

    private fun css(t: CharSequence, out: SpanBuilder) {
        val n = t.length
        var i = 0
        var depth = 0
        while (i < n) {
            val c = t[i]
            when {
                startsWith(t, "/*", i) -> {
                    val close = indexOf(t, "*/", i + 2)
                    val e = if (close < 0) n else close + 2
                    out.add(i, e, Role.COMMENT)
                    i = e
                }
                // SCSS and Less line comments; `//` inside url(…) follows a colon.
                startsWith(t, "//", i) && (i == 0 || t[i - 1] != ':') -> {
                    val e = lineEnd(t, i)
                    out.add(i, e, Role.COMMENT)
                    i = e
                }
                c == '"' || c == '\'' -> {
                    val e = stringEnd(t, i, multi = false)
                    out.add(i, e, Role.STRING)
                    i = e
                }
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    depth = maxOf(0, depth - 1)
                    i++
                }
                c == '@' -> {
                    var e = i + 1
                    while (e < n && (isIdent(t[e]) || t[e] == '-')) e++
                    out.add(i, e, Role.META)
                    i = e
                }
                c == '#' && depth > 0 -> {
                    var e = i + 1
                    while (e < n && t[e].isLetterOrDigit()) e++
                    out.add(i, e, Role.NUMBER)
                    i = e
                }
                c.isDigit() || (c == '.' && i + 1 < n && t[i + 1].isDigit() && depth > 0) -> {
                    var e = i + 1
                    while (e < n && (t[e].isLetterOrDigit() || t[e] == '.' || t[e] == '%')) e++
                    if (depth > 0) out.add(i, e, Role.NUMBER)
                    i = e
                }
                isIdentStart(c) || c == '-' || ((c == '.' || c == '#') && depth == 0) -> {
                    var e = i + 1
                    while (e < n && (isIdent(t[e]) || t[e] == '-')) e++
                    if (depth == 0) {
                        out.add(i, e, Role.TYPE)
                    } else {
                        var k = e
                        while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                        if (k < n && t[k] == ':') out.add(i, e, Role.KEYWORD)
                    }
                    i = e
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------ line-based formats

    /** Calls [line] with the start and end (before `\n`) of every line. */
    private inline fun lines(t: CharSequence, line: (Int, Int) -> Unit) {
        var s = 0
        while (s <= t.length) {
            val e = lineEnd(t, s)
            line(s, e)
            s = e + 1
        }
    }

    private fun firstNonBlank(t: CharSequence, s: Int, e: Int): Int {
        var i = s
        while (i < e && (t[i] == ' ' || t[i] == '\t')) i++
        return i
    }

    /** A plain value: quoted string, number or boolean; other text stays uncoloured. */
    private fun value(t: CharSequence, s: Int, e: Int, out: SpanBuilder) {
        val a = firstNonBlank(t, s, e)
        var b = e
        while (b > a && (t[b - 1] == ' ' || t[b - 1] == '\t' || t[b - 1] == '\r')) b--
        if (a >= b) return
        val c = t[a]
        if (c == '"' || c == '\'') {
            out.add(a, minOf(stringEnd(t, a, multi = false), b), Role.STRING)
            return
        }
        val w = t.subSequence(a, b).toString()
        when {
            w.lowercase() in BOOLEANS -> out.add(a, b, Role.KEYWORD)
            NUMBER.matches(w) -> out.add(a, b, Role.NUMBER)
            c == '&' || c == '*' || c == '!' -> {
                // YAML anchors, aliases and tags.
                var z = a
                while (z < b && !t[z].isWhitespace()) z++
                out.add(a, z, Role.META)
            }
        }
    }

    private val BOOLEANS = setOf("true", "false", "yes", "no", "on", "off", "null", "none", "~")
    private val NUMBER = Regex("""[-+]?(0x[0-9a-fA-F_]+|\d[\d_]*(\.\d+)?([eE][-+]?\d+)?)""")

    private fun keyValue(t: CharSequence, out: SpanBuilder, inlineComments: Boolean) {
        lines(t) { s, e ->
            val a = firstNonBlank(t, s, e)
            if (a >= e) return@lines
            val c = t[a]
            when {
                c == '#' || c == ';' || c == '!' -> out.add(a, e, Role.COMMENT)
                c == '[' -> out.add(a, e, Role.KEYWORD)
                else -> {
                    var sep = a
                    while (sep < e && t[sep] != '=' && t[sep] != ':') sep++
                    if (sep == e) return@lines
                    var keyEnd = sep
                    while (keyEnd > a && t[keyEnd - 1].isWhitespace()) keyEnd--
                    out.add(a, keyEnd, Role.TYPE)
                    var valueEnd = e
                    if (inlineComments) {
                        val hash = commentStart(t, sep + 1, e)
                        if (hash >= 0) {
                            out.add(hash, e, Role.COMMENT)
                            valueEnd = hash
                        }
                    }
                    value(t, sep + 1, valueEnd, out)
                }
            }
        }
    }

    /** A `#` after a space and outside quotes, or -1. */
    private fun commentStart(t: CharSequence, s: Int, e: Int): Int {
        var quote = 0.toChar()
        for (i in s until e) {
            val c = t[i]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == s || t[i - 1].isWhitespace()) -> return i
            }
        }
        return -1
    }

    private fun yaml(t: CharSequence, out: SpanBuilder) {
        lines(t) { s, e ->
            var a = firstNonBlank(t, s, e)
            if (a >= e) return@lines
            if (t[a] == '#') {
                out.add(a, e, Role.COMMENT)
                return@lines
            }
            if (a == s && (startsWith(t, "---", a) || startsWith(t, "...", a))) {
                out.add(a, e, Role.META)
                return@lines
            }
            var end = e
            val hash = commentStart(t, a, e)
            if (hash >= 0) {
                out.add(hash, e, Role.COMMENT)
                end = hash
            }
            while (a + 1 < end && t[a] == '-' && t[a + 1] == ' ') {
                out.add(a, a + 1, Role.NUMBER)
                a = firstNonBlank(t, a + 1, end)
            }
            // `key:` followed by a space or the line end.
            var k = a
            var quote = 0.toChar()
            while (k < end) {
                val c = t[k]
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                } else if (c == '"' || c == '\'') {
                    if (k == a) quote = c else break
                } else if (c == ':' && (k + 1 == end || t[k + 1] == ' ')) {
                    out.add(a, k, Role.TYPE)
                    value(t, k + 1, end, out)
                    return@lines
                }
                k++
            }
            value(t, a, end, out)
        }
    }

    private fun markdown(t: CharSequence, out: SpanBuilder) {
        var fence: String? = null
        lines(t) { s, e ->
            val a = firstNonBlank(t, s, e)
            val f = fence
            if (f != null) {
                out.add(s, e, if (startsWith(t, f, a)) Role.META else Role.STRING)
                if (startsWith(t, f, a)) fence = null
                return@lines
            }
            if (startsWith(t, "```", a) || startsWith(t, "~~~", a)) {
                fence = t.subSequence(a, a + 3).toString()
                out.add(s, e, Role.META)
                return@lines
            }
            if (a >= e) return@lines
            val c = t[a]
            var hashes = 0
            while (a + hashes < e && t[a + hashes] == '#') hashes++
            when {
                hashes in 1..6 && (a + hashes == e || t[a + hashes] == ' ') -> out.add(a, e, Role.HEADING)
                c == '>' -> out.add(a, e, Role.COMMENT)
                (c == '-' || c == '*' || c == '_') && RULE.matches(t.subSequence(a, e)) -> out.add(a, e, Role.META)
                else -> {
                    var body = a
                    val marker = LIST.find(t.subSequence(a, e))
                    if (marker != null) {
                        out.add(a, a + marker.value.trimEnd().length, Role.NUMBER)
                        body = a + marker.value.length
                    }
                    inline(t, body, e, out)
                }
            }
        }
    }

    private val RULE = Regex("""([-*_])( *\1){2,} *""")
    private val LIST = Regex("""^([-*+]|\d{1,9}[.)])( \[[ xX]])? +""")

    /** Markdown inline marks: code spans, bold, italic and link targets. */
    private fun inline(t: CharSequence, s: Int, e: Int, out: SpanBuilder) {
        var i = s
        while (i < e) {
            val c = t[i]
            when {
                c == '\\' -> i += 2
                c == '`' -> {
                    var j = i + 1
                    while (j < e && t[j] != '`') j++
                    if (j < e) {
                        out.add(i, j + 1, Role.STRING)
                        i = j + 1
                    } else {
                        i++
                    }
                }
                (c == '*' || c == '_') && i + 1 < e && t[i + 1] == c -> {
                    val close = indexOf(t, "$c$c", i + 2)
                    if (close in (i + 3)..(e - 2)) {
                        out.add(i, close + 2, Role.STRONG)
                        i = close + 2
                    } else {
                        i += 2
                    }
                }
                (c == '*' || c == '_') && i + 1 < e && !t[i + 1].isWhitespace() && (c == '*' || i == s || !isIdent(t[i - 1])) -> {
                    var j = i + 1
                    while (j < e && t[j] != c) j++
                    if (j < e && j > i + 1 && !t[j - 1].isWhitespace()) {
                        out.add(i, j + 1, Role.EMPHASIS)
                        i = j + 1
                    } else {
                        i++
                    }
                }
                c == ']' && i + 1 < e && t[i + 1] == '(' -> {
                    var j = i + 2
                    while (j < e && t[j] != ')') j++
                    out.add(i + 2, j, Role.TYPE)
                    i = j
                }
                else -> i++
            }
        }
    }

    private val TIMESTAMP = Regex("""\d{1,2}:\d{2}(:\d{2})?([.,]\d{1,3})?""")
    private val ASS_KEY = Regex("""(Dialogue|Comment|Style|Format|Title|ScriptType|PlayRes[XY]|WrapStyle|ScaledBorderAndShadow|YCbCr Matrix|Original \w+)\s*:""")
    private val SECTION = Regex("""^\[[A-Za-z][\w +]*]\s*$""")

    private fun subtitles(t: CharSequence, out: SpanBuilder) {
        lines(t) { s, e ->
            val a = firstNonBlank(t, s, e)
            if (a >= e) return@lines
            val line = t.subSequence(a, e).toString().trimEnd('\r')
            when {
                line.all { it.isDigit() } -> out.add(a, a + line.length, Role.NUMBER)
                line.startsWith("WEBVTT") || SECTION.matches(line) -> out.add(a, a + line.length, Role.KEYWORD)
                line.startsWith(";") || line.startsWith("NOTE") -> out.add(a, a + line.length, Role.COMMENT)
                else -> {
                    // LRC tags: [mm:ss.xx] and [ar:Artist] at the line start.
                    var i = 0
                    while (i < line.length && line[i] == '[') {
                        val close = line.indexOf(']', i)
                        if (close < 0) break
                        out.add(a + i, a + close + 1, Role.COMMENT)
                        i = close + 1
                    }
                    ASS_KEY.matchAt(line, i)?.let { out.add(a + i, a + it.range.last + 1, Role.TYPE) }
                    var j = i
                    while (j < line.length) {
                        val c = line[j]
                        val close = when (c) {
                            '{' -> line.indexOf('}', j)
                            '<' -> line.indexOf('>', j)
                            else -> -1
                        }
                        when {
                            close > j -> {
                                out.add(a + j, a + close + 1, Role.META)
                                j = close + 1
                            }
                            line.startsWith("-->", j) -> {
                                out.add(a + j, a + j + 3, Role.META)
                                j += 3
                            }
                            c.isDigit() && (j == 0 || !line[j - 1].isLetterOrDigit()) -> {
                                val m = TIMESTAMP.matchAt(line, j)
                                if (m != null) {
                                    out.add(a + j, a + m.range.last + 1, Role.COMMENT)
                                    j = m.range.last + 1
                                } else {
                                    j++
                                }
                            }
                            else -> j++
                        }
                    }
                }
            }
        }
    }

    private val LEVEL = Regex("""\b(FATAL|ERROR|SEVERE|CRITICAL|WARN|WARNING|INFO|NOTICE|DEBUG|TRACE|VERBOSE)\b""")

    /** Logcat: `10-24 12:00:00.000  1234  1234 E Tag: …` and the brief `E/Tag( 1234): …`. */
    private val LOGCAT = Regex("""^(?:\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+ ([VDIWEF]) |([VDIWEF])/)""")
    private val LOG_TIME = Regex("""(\d{4}-\d{2}-\d{2}[T ])?\d{2}:\d{2}:\d{2}([.,]\d+)?Z?|\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

    private fun levelRole(level: String): Role? = when (level) {
        "FATAL", "ERROR", "SEVERE", "CRITICAL", "E", "F" -> Role.ERROR
        "WARN", "WARNING", "W" -> Role.WARN
        "INFO", "NOTICE", "I" -> Role.TYPE
        "DEBUG", "TRACE", "VERBOSE", "D", "V" -> Role.COMMENT
        else -> null
    }

    private fun log(t: CharSequence, out: SpanBuilder) {
        lines(t) { s, e ->
            if (s >= e) return@lines
            val line = t.subSequence(s, e).toString()
            val trimmed = line.trimStart()
            when {
                // Java stack traces.
                trimmed.startsWith("at ") || (trimmed.startsWith("...") && trimmed.endsWith("more")) -> out.add(s, e, Role.COMMENT)
                trimmed.startsWith("Caused by:") -> out.add(s + line.length - trimmed.length, e, Role.ERROR)
                else -> {
                    val spans = ArrayList<Triple<Int, Int, Role>>()
                    LOG_TIME.find(line)?.takeIf { it.range.first < 30 }?.let { spans.add(Triple(it.range.first, it.range.last + 1, Role.COMMENT)) }
                    val cat = LOGCAT.find(line)
                    val g = cat?.groups?.get(1) ?: cat?.groups?.get(2)
                    if (g != null) {
                        levelRole(g.value)?.let { spans.add(Triple(g.range.first, g.range.last + 1, it)) }
                    } else {
                        LEVEL.find(line)?.let { m -> levelRole(m.value)?.let { spans.add(Triple(m.range.first, m.range.last + 1, it)) } }
                    }
                    spans.sortBy { it.first }
                    var last = 0
                    for ((a, b, r) in spans) {
                        if (a < last) continue
                        out.add(s + a, s + b, r)
                        last = b
                    }
                }
            }
        }
    }

    private fun diff(t: CharSequence, out: SpanBuilder) {
        lines(t) { s, e ->
            if (s >= e) return@lines
            when {
                startsWith(t, "+++", s) || startsWith(t, "---", s) || startsWith(t, "diff ", s) || startsWith(t, "index ", s) ->
                    out.add(s, e, Role.KEYWORD)
                startsWith(t, "@@", s) -> out.add(s, e, Role.META)
                t[s] == '+' -> out.add(s, e, Role.ADDED)
                t[s] == '-' -> out.add(s, e, Role.ERROR)
            }
        }
    }

    /** One colour per column ("rainbow CSV"). */
    private val COLUMNS = arrayOf(Role.KEYWORD, Role.STRING, Role.TYPE, Role.NUMBER, Role.META, Role.ADDED)

    private fun csv(t: CharSequence, out: SpanBuilder, delimiter: Char?) {
        val n = t.length
        val sep = delimiter ?: run {
            val first = t.subSequence(0, lineEnd(t, 0))
            listOf(',', ';', '\t', '|').maxBy { d -> first.count { it == d } }
        }
        var i = 0
        var column = 0
        var fieldStart = 0
        var quoted = false
        while (i <= n) {
            val c = if (i < n) t[i] else '\n'
            when {
                quoted -> if (c == '"') {
                    if (i + 1 < n && t[i + 1] == '"') i++ else quoted = false
                }
                c == '"' && i == fieldStart -> quoted = true
                c == sep || c == '\n' -> {
                    out.add(fieldStart, i, COLUMNS[column % COLUMNS.size])
                    column = if (c == '\n') 0 else column + 1
                    fieldStart = i + 1
                }
            }
            i++
        }
    }
}
