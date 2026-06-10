package dev.nymph.aireader.playback.synth

import dev.nymph.aireader.model.TtsError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After

class SynthesisSchedulerTest {

    // Workers must run as FOREGROUND tasks on the test scheduler:
    // advanceUntilIdle() does not execute backgroundScope work in
    // kotlinx-coroutines-test 1.9 (only runCurrent-style interleaving does).
    private val ownedScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        ownedScopes.forEach { it.cancel() }
    }

    private fun config(
        lookahead: Int = 2,
        maxAheadBytes: Long = Long.MAX_VALUE,
    ) = SynthesisConfig(
        lookaheadSentences = lookahead,
        maxAheadBytes = maxAheadBytes,
        fallbackSampleRate = 24_000,
        fallbackSilenceMs = 300,
        writeRetryDelayMs = 1_000,
    )

    private fun TestScope.scheduler(
        engine: FakeTtsEngine,
        store: InMemoryChunkStore,
        size: Int,
        pauseMs: Int = 0,
        config: SynthesisConfig = config(),
    ): SynthesisScheduler {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        ownedScopes += scope
        return SynthesisScheduler(sentences(size, pauseMs), engine, testVoice, store, scope, config)
    }

    @Test
    fun `synthesizes the whole document in order and completes`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 5)
        s.start(0)
        advanceUntilIdle()
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), engine.calls)
        assertEquals(setOf(0, 1, 2, 3, 4), store.written.keys)
        val st = s.state.value
        assertTrue(st.isComplete)
        assertFalse(st.isSynthesizing)
        assertEquals(4, st.cachedThrough)
        assertNull(st.lastError)
    }

    @Test
    fun `skips chunks that are already cached`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        store.written[0] = ByteArray(4)
        store.written[1] = ByteArray(4)
        val s = scheduler(engine, store, size = 5)
        s.start(0)
        advanceUntilIdle()
        assertEquals(listOf("s2", "s3", "s4"), engine.calls)
        assertTrue(s.state.value.isComplete)
    }

    @Test
    fun `with no byte budget only the window is filled and it follows the playhead`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 10, config = config(lookahead = 2, maxAheadBytes = 0))
        s.start(0)
        advanceUntilIdle()
        assertEquals(setOf(0, 1), store.written.keys)
        val idle = s.state.value
        assertEquals(1, idle.cachedThrough)
        assertFalse(idle.isSynthesizing)
        assertFalse(idle.isComplete)

        s.setPlayhead(1) // window [1, 3)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2), store.written.keys)

        s.setPlayhead(2) // window [2, 4)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2, 3), store.written.keys)
        assertEquals(3, s.state.value.cachedThrough)
    }

    @Test
    fun `a far seek interrupts the idle wait and retargets immediately`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 10, config = config(lookahead = 2, maxAheadBytes = 0))
        s.start(0)
        advanceUntilIdle()
        assertEquals(setOf(0, 1), store.written.keys) // idle-waiting now

        s.setPlayhead(7)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 7, 8), store.written.keys) // nothing in between synthesized
        assertEquals(listOf("s0", "s1", "s7", "s8"), engine.calls)
        val st = s.state.value
        assertEquals(7, st.playhead)
        assertEquals(8, st.cachedThrough)
    }

    @Test
    fun `a seek during synthesis lets the current chunk finish then retargets`() = runTest {
        val engine = FakeTtsEngine()
        engine.gate = Channel(Channel.UNLIMITED)
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 10, config = config(lookahead = 2, maxAheadBytes = 0))
        s.start(0)
        runCurrent()
        assertEquals(listOf("s0"), engine.calls) // in flight, parked at the gate
        assertTrue(s.state.value.isSynthesizing)

        s.setPlayhead(5)
        runCurrent()
        assertEquals(listOf("s0"), engine.calls) // not cancelled, not duplicated

        engine.gate!!.send(Unit) // let s0 finish
        runCurrent()
        assertTrue(store.has(0)) // the in-flight chunk was still written
        assertEquals(listOf("s0", "s5"), engine.calls) // next target is the new window
    }

    @Test
    fun `synthesis failure writes a silence chunk and the queue continues`() = runTest {
        val engine = FakeTtsEngine(sampleRate = 24_000, samplesPerSentence = 2_400)
        engine.failTexts = setOf("s2")
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 5, pauseMs = 100)
        s.start(0)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2, 3, 4), store.written.keys)
        assertTrue(s.state.value.isComplete)
        assertIs<TtsError.SynthesisFailed>(s.state.value.lastError)
        // failed chunk is fallback silence (300ms) + pause (100ms) at the last known rate
        val silence = store.written.getValue(2)
        assertEquals(44 + 2 * (24_000 * 400 / 1000), silence.size)
        // a healthy chunk is engine audio + pause
        val healthy = store.written.getValue(1)
        assertEquals(44 + 2 * (2_400 + 24_000 * 100 / 1000), healthy.size)
    }

    @Test
    fun `never synthesizes the same index twice even with playhead jitter`() = runTest {
        val engine = FakeTtsEngine()
        engine.gate = Channel(Channel.UNLIMITED)
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 6, config = config(lookahead = 2))
        s.start(0)
        runCurrent() // s0 in flight
        s.setPlayhead(3)
        engine.gate!!.send(Unit)
        runCurrent() // s0 written, s3 in flight
        s.setPlayhead(0)
        engine.gate!!.send(Unit)
        runCurrent() // s3 written, next from playhead 0
        repeat(20) { engine.gate!!.send(Unit) }
        advanceUntilIdle()
        assertTrue(s.state.value.isComplete)
        assertEquals(engine.calls.distinct(), engine.calls)
        assertEquals(setOf(0, 1, 2, 3, 4, 5), store.written.keys)
    }

    @Test
    fun `empty document is complete immediately`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 0)
        s.start(0)
        advanceUntilIdle()
        assertTrue(s.state.value.isComplete)
        assertEquals(emptyList(), engine.calls)
    }

    @Test
    fun `stop cancels the worker without further synthesis`() = runTest {
        val engine = FakeTtsEngine()
        engine.gate = Channel(Channel.UNLIMITED)
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 5)
        s.start(0)
        runCurrent()
        assertEquals(listOf("s0"), engine.calls)
        s.stop()
        engine.gate!!.send(Unit)
        advanceUntilIdle()
        assertEquals(listOf("s0"), engine.calls) // nothing new after stop
        assertEquals(emptySet(), store.written.keys)
    }

    @Test
    fun `cache write failure retries after a delay instead of spinning or deadlocking`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        store.failWrites = true
        val s = scheduler(engine, store, size = 2)
        s.start(0)
        runCurrent()
        assertEquals(listOf("s0"), engine.calls) // one attempt, then waiting out the retry delay
        assertIs<TtsError.SynthesisFailed>(s.state.value.lastError)

        store.failWrites = false
        advanceTimeBy(1_001)
        advanceUntilIdle()
        assertEquals(setOf(0, 1), store.written.keys)
        assertTrue(s.state.value.isComplete)
    }

    @Test
    fun `playhead is clamped to the document bounds`() = runTest {
        val engine = FakeTtsEngine()
        val store = InMemoryChunkStore()
        val s = scheduler(engine, store, size = 3, config = config(lookahead = 1, maxAheadBytes = 0))
        s.start(-5)
        advanceUntilIdle()
        assertEquals(setOf(0), store.written.keys)
        s.setPlayhead(99)
        advanceUntilIdle()
        assertEquals(2, s.state.value.playhead)
        assertEquals(setOf(0, 2), store.written.keys)
    }
}
