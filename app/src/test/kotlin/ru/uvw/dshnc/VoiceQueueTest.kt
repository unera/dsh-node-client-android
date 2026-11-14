package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.VoiceChat
import ru.uvw.dshnc.logic.VoiceInbox
import ru.uvw.dshnc.logic.VoiceSpeaker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The two queues that keep the command loop free.
 *
 * One for the screen: a command hands its line over and answers at once. One for the mouth: a
 * sentence always finishes before the next one starts, because a person notices an interrupted
 * remark immediately and never forgives it.
 */
class VoiceQueueTest {

    @Test
    fun `the inbox keeps the order and empties on drain` () {
        VoiceInbox.clear()
        val seen = mutableListOf<String>()
        VoiceInbox.onPosted = { seen.add("nudge") }
        try {
            VoiceInbox.post(VoiceInbox.Item.Chat("a", VoiceChat.Role.USER, "первый"))
            VoiceInbox.post(VoiceInbox.Item.Activity("a", 17, "bash"))
            VoiceInbox.post(VoiceInbox.Item.Chat("a", VoiceChat.Role.ASSISTANT, "второй"))
            assertEquals(3, VoiceInbox.size())
            // the nudge is what tells the screen to come and take them
            assertEquals(3, seen.size)
            val drained = VoiceInbox.drain()
            assertEquals(
                listOf("первый", "17", "второй"),
                drained.map {
                    when (it) {
                        is VoiceInbox.Item.Chat -> it.text
                        is VoiceInbox.Item.Activity -> it.n.toString()
                    }
                },
            )
            assertEquals(0, VoiceInbox.size())
            assertTrue(VoiceInbox.drain().isEmpty())
        } finally {
            VoiceInbox.onPosted = null
            VoiceInbox.clear()
        }
    }

    @Test
    fun `speech never interrupts itself and keeps the order` () {
        val finished = CountDownLatch(3)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val busy = AtomicInteger(0)
        val speaker = VoiceSpeaker(
            speakText = { text ->
                assertEquals(1, busy.incrementAndGet())
                Thread.sleep(20)
                order.add(text)
                busy.decrementAndGet()
                finished.countDown()
            },
            playAudio = { _, _ -> },
        )
        try {
            speaker.submit(VoiceSpeaker.Task(id = "1", text = "первая"))
            speaker.submit(VoiceSpeaker.Task(id = "2", text = "вторая"))
            speaker.submit(VoiceSpeaker.Task(id = "3", text = "третья"))
            assertTrue("speech did not finish in time", finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("первая", "вторая", "третья"), order)
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `a failed sentence does not silence the rest` () {
        // one sentence fails, the next one still has to be spoken
        val finished = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val speaker = VoiceSpeaker(
            speakText = { text ->
                if (text == "плохая") throw IllegalStateException("нет движка")
                order.add(text)
                finished.countDown()
            },
            playAudio = { _, _ -> },
        )
        try {
            speaker.submit(VoiceSpeaker.Task(id = "1", text = "плохая"))
            speaker.submit(VoiceSpeaker.Task(id = "2", text = "хорошая"))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("хорошая"), order)
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `an audio task goes to the player, not to the engine` () {
        val spoken = AtomicReference<String>("")
        val played = AtomicReference<String>("")
        val playedSeq = AtomicReference(0L)
        val done = CountDownLatch(1)
        val speaker = VoiceSpeaker(
            speakText = { text -> spoken.set(text) },
            playAudio = { audio, seq ->
                played.set(audio)
                playedSeq.set(seq)
                done.countDown()
            },
        )
        try {
            speaker.submit(VoiceSpeaker.Task(id = "x", text = "не говорить", audio = "/var/lib/dsh/voice/a.wav", seq = 42))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("/var/lib/dsh/voice/a.wav", played.get())
            assertEquals(42L, playedSeq.get())
            assertEquals("", spoken.get())
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `a submitted task is queued at once, not run in the caller's thread` () {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val speaker = VoiceSpeaker(
            speakText = {
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
            },
            playAudio = { _, _ -> },
        )
        try {
            val antes = System.currentTimeMillis()
            speaker.submit(VoiceSpeaker.Task(id = "1", text = "долгая фраза"))
            val took = System.currentTimeMillis() - antes
            assertTrue("submit must not wait for the speech, took ${took}ms", took < 1000)
            assertTrue(started.await(5, TimeUnit.SECONDS))
        } finally {
            gate.countDown()
            speaker.shutdown()
        }
    }
}
