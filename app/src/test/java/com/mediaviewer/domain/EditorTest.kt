package com.mediaviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    /** The text and role of every span, for readable assertions. */
    private fun colours(text: String, lang: Language): List<Pair<String, Role>> {
        val s = Highlight.spans(text, lang)
        return (0 until s.size).map { text.substring(s.start(it), s.end(it)) to s.role(it) }
    }

    private fun roleOf(text: String, lang: Language, part: String): Role? = colours(text, lang).firstOrNull { it.first == part }?.second

    @Test fun languageFromName() {
        assertEquals(Language.KOTLIN, Highlight.language("Main.kt"))
        assertEquals(Language.MARKDOWN, Highlight.language("README.MD"))
        assertEquals(Language.MAKE, Highlight.language("Makefile"))
        assertEquals(Language.PLAIN, Highlight.language("notes"))
        assertEquals(Language.PLAIN, Highlight.language("notes.txt"))
        assertEquals(Language.PLAIN, Highlight.language("notes.xyz"))
        assertEquals(Language.INI, Highlight.language(".gitignore"))
        assertEquals(Language.PLAIN, Highlight.language("trailing."))
    }

    @Test fun codeKeywordsStringsCommentsNumbers() {
        val src = "val x = \"a // b\" // note\nfun f(): Int = 42 /* c */"
        val c = colours(src, Language.KOTLIN)
        assertTrue(("val" to Role.KEYWORD) in c)
        assertTrue(("\"a // b\"" to Role.STRING) in c)
        assertTrue(("// note" to Role.COMMENT) in c)
        assertTrue(("Int" to Role.TYPE) in c)
        assertTrue(("42" to Role.NUMBER) in c)
        assertTrue(("/* c */" to Role.COMMENT) in c)
        assertNull(roleOf(src, Language.KOTLIN, "x"))
    }

    @Test fun pythonTripleStringsAndDecorators() {
        val src = "@cache\ndef f():\n    \"\"\"doc\n    more\"\"\"\n    return None  # done"
        val c = colours(src, Language.PYTHON)
        assertTrue(("@cache" to Role.META) in c)
        assertTrue(("\"\"\"doc\n    more\"\"\"" to Role.STRING) in c)
        assertTrue(("None" to Role.KEYWORD) in c)
        assertTrue(("# done" to Role.COMMENT) in c)
    }

    @Test fun rustLifetimeIsNotAString() {
        val src = "fn f<'a>(x: &'a str) -> char { 'x' }"
        val c = colours(src, Language.RUST)
        assertTrue(("'x'" to Role.STRING) in c)
        assertTrue(c.none { it.second == Role.STRING && it.first.startsWith("'a") })
    }

    @Test fun shellCommentsNeedASpace() {
        val src = "echo $# \$HOME \${PATH} # comment"
        val c = colours(src, Language.SHELL)
        assertTrue(("# comment" to Role.COMMENT) in c)
        assertTrue(("\$HOME" to Role.META) in c)
        assertTrue(("\${PATH}" to Role.META) in c)
        assertEquals(1, c.count { it.second == Role.COMMENT })
    }

    @Test fun cPreprocessorAndSqlIgnoreCase() {
        assertEquals(Role.META, roleOf("#include <stdio.h>\nint x;", Language.C, "#include <stdio.h>"))
        assertEquals(Role.KEYWORD, roleOf("Select * FROM t -- all", Language.SQL, "Select"))
        assertEquals(Role.COMMENT, roleOf("Select * FROM t -- all", Language.SQL, "-- all"))
    }

    @Test fun markup() {
        val src = "<a href=\"x\" id='y'>T &amp; U</a><!-- c -->"
        val c = colours(src, Language.HTML)
        assertTrue(("<a" to Role.KEYWORD) in c)
        assertTrue(("href" to Role.TYPE) in c)
        assertTrue(("\"x\"" to Role.STRING) in c)
        assertTrue(("&amp;" to Role.META) in c)
        assertTrue(("</a" to Role.KEYWORD) in c)
        assertTrue(("<!-- c -->" to Role.COMMENT) in c)
    }

    @Test fun jsonKeysAndValues() {
        val c = colours("{\"k\": \"v\", \"n\": -1.5e3, \"b\": true}", Language.JSON)
        assertTrue(("\"k\"" to Role.TYPE) in c)
        assertTrue(("\"v\"" to Role.STRING) in c)
        assertTrue(("-1.5e3" to Role.NUMBER) in c)
        assertTrue(("true" to Role.KEYWORD) in c)
    }

    @Test fun cssSelectorsPropertiesValues() {
        val c = colours(".a #b { color: #fff; margin: 2px; } @media x {}", Language.CSS)
        assertTrue((".a" to Role.TYPE) in c)
        assertTrue(("color" to Role.KEYWORD) in c)
        assertTrue(("#fff" to Role.NUMBER) in c)
        assertTrue(("2px" to Role.NUMBER) in c)
        assertTrue(("@media" to Role.META) in c)
    }

    @Test fun configFiles() {
        val ini = colours("; c\n[main]\nname = \"x\"\ncount=3\nflag: on", Language.INI)
        assertTrue(("; c" to Role.COMMENT) in ini)
        assertTrue(("[main]" to Role.KEYWORD) in ini)
        assertTrue(("name" to Role.TYPE) in ini)
        assertTrue(("\"x\"" to Role.STRING) in ini)
        assertTrue(("3" to Role.NUMBER) in ini)
        assertTrue(("on" to Role.KEYWORD) in ini)
        val toml = colours("a = 1 # one\nurl = \"http://x#y\"", Language.TOML)
        assertTrue(("# one" to Role.COMMENT) in toml)
        assertTrue(("\"http://x#y\"" to Role.STRING) in toml)
        val yaml = colours("---\nkey: value # c\nlist:\n  - 12\n  - name: &a true\nurl: http://x", Language.YAML)
        assertTrue(("---" to Role.META) in yaml)
        assertTrue(("key" to Role.TYPE) in yaml)
        assertTrue(("# c" to Role.COMMENT) in yaml)
        assertTrue(("12" to Role.NUMBER) in yaml)
        assertTrue(("name" to Role.TYPE) in yaml)
        assertTrue(("url" to Role.TYPE) in yaml)
        assertTrue(yaml.none { it.first.startsWith("http") && it.second == Role.TYPE })
    }

    @Test fun markdown() {
        val src = "# Title\n- item **bold** and `code`\n> quote\n```\nx = 1\n```\n[link](http://a) *em*"
        val c = colours(src, Language.MARKDOWN)
        assertTrue(("# Title" to Role.HEADING) in c)
        assertTrue(("-" to Role.NUMBER) in c)
        assertTrue(("**bold**" to Role.STRONG) in c)
        assertTrue(("`code`" to Role.STRING) in c)
        assertTrue(("> quote" to Role.COMMENT) in c)
        assertTrue(("x = 1" to Role.STRING) in c)
        assertTrue(("http://a" to Role.TYPE) in c)
        assertTrue(("*em*" to Role.EMPHASIS) in c)
    }

    @Test fun subtitles() {
        val srt = colours("1\n00:00:01,000 --> 00:00:02,500\n<i>Hi</i> there", Language.SUBTITLES)
        assertTrue(("1" to Role.NUMBER) in srt)
        assertTrue(("00:00:01,000" to Role.COMMENT) in srt)
        assertTrue(("-->" to Role.META) in srt)
        assertTrue(("<i>" to Role.META) in srt)
        val lrc = colours("[ar:Band]\n[00:12.50]Words", Language.SUBTITLES)
        assertTrue(("[00:12.50]" to Role.COMMENT) in lrc)
        val ass = colours("[Events]\nDialogue: 0,0:00:01.00,Default,{\\i1}Hi", Language.SUBTITLES)
        assertTrue(("[Events]" to Role.KEYWORD) in ass)
        assertTrue(("Dialogue:" to Role.TYPE) in ass)
        assertTrue(("{\\i1}" to Role.META) in ass)
    }

    @Test fun logs() {
        val c = colours(
            "2026-10-24 12:00:01.123 ERROR boom\n10-24 12:00:00.000  123  456 W Tag: careful\n\tat a.b(C.java:1)\nINFO ok",
            Language.LOG,
        )
        assertTrue(("2026-10-24 12:00:01.123" to Role.COMMENT) in c)
        assertTrue(("ERROR" to Role.ERROR) in c)
        assertTrue(("W" to Role.WARN) in c)
        assertTrue(("\tat a.b(C.java:1)" to Role.COMMENT) in c)
        assertTrue(("INFO" to Role.TYPE) in c)
    }

    @Test fun diffAndCsv() {
        val d = colours("--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n same", Language.DIFF)
        assertTrue(("-old" to Role.ERROR) in d)
        assertTrue(("+new" to Role.ADDED) in d)
        assertTrue(("@@ -1 +1 @@" to Role.META) in d)
        val c = colours("a;b;\"c;d\"\n1;2;3", Language.CSV)
        assertEquals(listOf("a", "b", "\"c;d\"", "1", "2", "3"), c.map { it.first })
        assertEquals(c[0].second, c[3].second)
        assertTrue(c[0].second != c[1].second)
    }

    @Test fun lexersNeverThrowOnOddInput() {
        val odd = listOf("", "\n", "\"", "'", "/*", "<", "<a", "<!--", "```", "**", "[", "{", "\\", "a:\n", "#", "$", "\${", "%", "1e", "0x")
        for (lang in Language.entries) for (t in odd) Highlight.spans(t, lang)
    }

    @Test fun textChange() {
        assertNull(TextChange.between("abc", "abc"))
        assertEquals(TextChange(1, 0, 2), TextChange.between("ac", "axxc"))
        assertEquals(TextChange(1, 1, 0), TextChange.between("abc", "ac"))
        assertEquals(TextChange(0, 3, 1), TextChange.between("abc", "z"))
        // Repeated letters: the change is placed after the common prefix.
        assertEquals(TextChange(2, 0, 1), TextChange.between("aa", "aaa"))
    }

    @Test fun spansFollowEdits() {
        val text = "val x = \"s\""
        val spans = Highlight.spans(text, Language.KOTLIN)
        fun show(s: Spans, t: String) = (0 until s.size).map { t.substring(s.start(it), s.end(it)) }
        // Typing before everything moves all spans.
        val t1 = "  $text"
        assertEquals(listOf("val", "\"s\""), show(spans.shifted(TextChange.between(text, t1)!!), t1))
        // Typing inside the string makes it longer; typing right after "val" doesn't make "val" longer.
        val t2 = "val x = \"sss\""
        assertEquals(listOf("val", "\"sss\""), show(spans.shifted(TextChange.between(text, t2)!!), t2))
        val t3 = "valz x = \"s\""
        assertEquals(listOf("val", "\"s\""), show(spans.shifted(TextChange.between(text, t3)!!), t3))
        // Deleting the keyword removes its span.
        val t4 = " x = \"s\""
        assertEquals(listOf("\"s\""), show(spans.shifted(TextChange.between(text, t4)!!), t4))
    }

    @Test fun undoGroupsTyping() {
        val h = EditHistory()
        h.record("", "a", 0, 0, 0)
        h.record("a", "ab", 1, 1, 100)
        h.record("ab", "abc", 2, 2, 200)
        // A pause starts a new step.
        h.record("abc", "abcd", 3, 3, 5000)
        assertEquals("abc", h.undo("abcd")!!.text)
        val step = h.undo("abc")!!
        assertEquals("", step.text)
        assertEquals(0, step.selStart)
        assertFalse(h.canUndo)
        assertEquals("abc", h.redo("")!!.text)
        assertEquals("abcd", h.redo("abc")!!.text)
        assertFalse(h.canRedo)
    }

    @Test fun undoGroupsBackspaceAndNewEditClearsRedo() {
        val h = EditHistory()
        h.record("hello", "hell", 5, 5, 0)
        h.record("hell", "hel", 4, 4, 100)
        val s = h.undo("hel")!!
        assertEquals("hello", s.text)
        assertEquals(5, s.selStart)
        assertTrue(h.canRedo)
        h.record("hello", "hello!", 5, 5, 200)
        assertFalse(h.canRedo)
        // Right after an undo, typing is a step of its own.
        assertEquals("hello", h.undo("hello!")!!.text)
        assertTrue(h.canUndo.not())
    }

    @Test fun newLineStartsANewUndoStep() {
        val h = EditHistory()
        h.record("", "a", 0, 0, 0)
        h.record("a", "a\n", 1, 1, 10)
        assertEquals("a", h.undo("a\n")!!.text)
    }

    // ------------------------------------------------------------------ themes (todo §10.8)

    @Test fun everyThemeColourReachesWcagAa() {
        val failures = ArrayList<String>()
        for (t in EditorThemes.PALETTES) {
            fun check(what: String, c: Long) {
                val r = Contrast.ratio(c, t.background)
                if (r < 4.5) failures.add("${t.name} (${if (t.dark) "dark" else "light"}) $what %08X: %.2f".format(c, r))
            }
            check("text", t.text)
            for (role in Role.entries) check(role.name, t.colour(role))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun contrastMatchesWcagExamples() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF777777, 0xFF777777), 0.001)
        // #767676 on white is the usual "just passes AA" grey.
        assertEquals(4.54, Contrast.ratio(0xFF767676, 0xFFFFFFFF), 0.01)
    }

    @Test fun themesResolve() {
        assertEquals(EditorThemes.DEFAULT_LIGHT, EditorThemes.resolve("", appDark = false))
        assertEquals(EditorThemes.DEFAULT_DARK, EditorThemes.resolve("default", appDark = true))
        assertEquals(EditorThemes.DEFAULT_DARK, EditorThemes.resolve("no-such-theme", appDark = true))
        // A chosen theme stays as chosen, whatever the app's day / night.
        assertEquals(EditorThemes.SOLARIZED_LIGHT, EditorThemes.resolve("solarized-light", appDark = true))
        assertEquals(EditorThemes.MONOKAI, EditorThemes.resolve("monokai", appDark = false))
        assertEquals(EditorThemes.PALETTES.size, EditorThemes.PALETTES.map { it.dark to it.id }.toSet().size)
        assertEquals(9, EditorThemes.CHOICES.size)
        assertTrue(EditorThemes.PALETTES.all { (it.dark) == (Contrast.luminance(it.background) < 0.2) })
    }
}
