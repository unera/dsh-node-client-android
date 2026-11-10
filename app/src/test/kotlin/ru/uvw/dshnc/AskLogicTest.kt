package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.AskButton
import ru.uvw.dshnc.logic.AskCheck
import ru.uvw.dshnc.logic.AskLogic
import ru.uvw.dshnc.logic.DialogStyle
import ru.uvw.dshnc.logic.RawButton
import ru.uvw.dshnc.logic.Timeouts

class AskLogicTest {
    private fun button (action: String? = "ok", title: String? = "OK", icon: String? = null, style: String? = null) =
        RawButton(action = action, title = title, icon = icon, style = style)

    private fun ready (check: AskCheck): AskCheck.Ready {
        assertTrue("expected a ready request, got $check", check is AskCheck.Ready)
        return check as AskCheck.Ready
    }

    private fun refused (check: AskCheck): AskCheck.Refused {
        assertTrue("expected a refusal, got $check", check is AskCheck.Refused)
        return check as AskCheck.Refused
    }

    @Test
    fun `a plain text question with two buttons is ready` () {
        val request = ready(AskLogic.check(
            type = "text",
            content = "Deploy?",
            title = "Question",
            buttons = listOf(button("yes", "Yes"), button("no", "No", "\u274C")),
            style = null,
        )).request
        assertEquals("Deploy?", request.content)
        assertEquals("Question", request.title)
        assertEquals(2, request.buttons.size)
        assertEquals("yes", request.buttons[0].action)
        assertEquals("\u274C", request.buttons[1].icon)
        assertEquals(DialogStyle.NORMAL, request.style)
    }

    @Test
    fun `an absent type means text and a foreign type is refused` () {
        val absent = AskLogic.check(null, "text", null, listOf(button()), null)
        assertTrue(absent is AskCheck.Ready)
        val foreign = refused(AskLogic.check("confirm", "text", null, listOf(button()), null))
        assertEquals("bad_args", foreign.code)
        assertEquals("bad_args", refused(AskLogic.check("TEXT_PLAIN", "text", null, listOf(button()), null)).code)
    }

    @Test
    fun `empty content is refused` () {
        assertEquals("bad_args", refused(AskLogic.check("text", "   ", null, listOf(button()), null)).code)
        assertEquals("bad_args", refused(AskLogic.check("text", null, null, listOf(button()), null)).code)
    }

    @Test
    fun `the button count must be one to three` () {
        assertEquals("bad_args", refused(AskLogic.check("text", "x", null, emptyList(), null)).code)
        val four = List(4) { button("a$it", "A$it") }
        assertEquals("bad_args", refused(AskLogic.check("text", "x", null, four, null)).code)
        val three = List(3) { button("a$it", "A$it") }
        assertEquals(3, ready(AskLogic.check("text", "x", null, three, null)).request.buttons.size)
    }

    @Test
    fun `every button needs an action and a title` () {
        assertEquals("bad_args", refused(AskLogic.check("text", "x", null, listOf(button(action = " ")), null)).code)
        assertEquals("bad_args", refused(AskLogic.check("text", "x", null, listOf(button(action = null)), null)).code)
        assertEquals("bad_args", refused(AskLogic.check("text", "x", null, listOf(button(title = "")), null)).code)
        assertEquals("bad_args", refused(AskLogic.check("text", "x", null, listOf(button(title = null)), null)).code)
    }

    @Test
    fun `a duplicate action is refused` () {
        val buttons = listOf(button("yes", "Yes"), button("yes", "Yes, really"))
        assertEquals("bad_args", refused(AskLogic.check("text", "x", null, buttons, null)).code)
    }

    @Test
    fun `styles map and an unknown style falls back to normal` () {
        assertEquals(DialogStyle.NORMAL, AskLogic.style(null))
        assertEquals(DialogStyle.NORMAL, AskLogic.style(""))
        assertEquals(DialogStyle.NORMAL, AskLogic.style("fancy"))
        assertEquals(DialogStyle.WARNING, AskLogic.style("warning"))
        assertEquals(DialogStyle.DANGER, AskLogic.style(" Danger "))
        val request = ready(AskLogic.check(
            "text", "x", null,
            listOf(button("go", "Go", style = "danger"), button("wait", "Wait", style = "nonsense")),
            "warning",
        )).request
        assertEquals(DialogStyle.DANGER, request.buttons[0].style)
        assertEquals(DialogStyle.NORMAL, request.buttons[1].style)
        assertEquals(DialogStyle.WARNING, request.style)
    }

    @Test
    fun `a press resolves to its action and everything else to cancel` () {
        val buttons = listOf(
            AskButton("yes", "Yes", "", DialogStyle.NORMAL),
            AskButton("no", "No", "", DialogStyle.NORMAL),
        )
        assertEquals("yes", AskLogic.resolveAction(buttons, 0))
        assertEquals("no", AskLogic.resolveAction(buttons, 1))
        assertEquals(AskLogic.CANCEL_ACTION, AskLogic.resolveAction(buttons, null))
        assertEquals(AskLogic.CANCEL_ACTION, AskLogic.resolveAction(buttons, -1))
        assertEquals(AskLogic.CANCEL_ACTION, AskLogic.resolveAction(buttons, 2))
        assertEquals(AskLogic.CANCEL_ACTION, AskLogic.resolveAction(emptyList(), 0))
    }

    @Test
    fun `the question has no pause of its own` () {
        // nobody named anything: the pause is the shared default of every method
        assertEquals(Timeouts.DEFAULT_MS.toLong(), AskLogic.timeoutMs(null, null, null))
        assertEquals(Timeouts.DEFAULT_MS.toLong(), AskLogic.timeoutMs(null, null, 0.0))
        // the command's own pause is taken as it is, and the arguments win over it
        assertEquals(600_000L, AskLogic.timeoutMs(null, null, 600.0))
        assertEquals(30_000L, AskLogic.timeoutMs(null, 30.0, 600.0))
        assertEquals(2_500L, AskLogic.timeoutMs(2500.0, 30.0, 600.0))
        // no limit of its own: a long wait is the caller's decision, not the phone's
        assertEquals(86_400_000L, AskLogic.timeoutMs(null, null, 86_400.0))
    }
}
