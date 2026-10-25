package com.github.avbox.osc.player.state

import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.player.core.PlaybackStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateMachineTest {

    private class Sequence(
        val name: String,
        val expected: PlayState,
        val steps: List<PlaybackStateMachine.() -> Unit>,
    )

    @Test
    fun actionAndEventSequencesReachExpectedStates() {
        val cases = listOf(
            Sequence("initial state", PlayState.IDLE, listOf()),
            Sequence(
                "prepare chain reaches playing",
                PlayState.PLAYING,
                listOf({ onPrepareRequested() }, { onPrepared() }, { onRenderingStart() }),
            ),
            Sequence(
                "buffering start after playing",
                PlayState.BUFFERING,
                listOf({ onPrepareRequested() }, { onPrepared() }, { onRenderingStart() }, { onBufferingStart() }),
            ),
            Sequence(
                "buffering end resumes playing",
                PlayState.PLAYING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onBufferingStart() },
                    { onBufferingEnd(true) },
                ),
            ),
            Sequence(
                "buffering end without playback stays buffered",
                PlayState.BUFFERED,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onBufferingStart() },
                    { onBufferingEnd(false) },
                ),
            ),
            Sequence(
                "pause after playing",
                PlayState.PAUSED,
                listOf({ onPrepareRequested() }, { onPrepared() }, { onRenderingStart() }, { onPauseRequested() }),
            ),
            Sequence(
                "play request resumes",
                PlayState.PLAYING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onPlayRequested() },
                ),
            ),
            Sequence(
                "completion",
                PlayState.COMPLETED,
                listOf({ onPrepareRequested() }, { onPrepared() }, { onRenderingStart() }, { onCompletion() }),
            ),
            Sequence(
                "error",
                PlayState.ERROR,
                listOf({ onPrepareRequested() }, { onPrepared() }, { onRenderingStart() }, { onError() }),
            ),
            Sequence(
                "stop request goes idle",
                PlayState.IDLE,
                listOf({ onPrepareRequested() }, { onPrepared() }, { onRenderingStart() }, { onStopRequested() }),
            ),
            Sequence(
                "reset goes idle",
                PlayState.IDLE,
                listOf({ onPrepareRequested() }, { onReset() }),
            ),
            Sequence(
                "start abort",
                PlayState.START_ABORT,
                listOf({ onStartAborted() }),
            ),
            Sequence(
                "start abort then prepare recovers",
                PlayState.PLAYING,
                listOf({ onStartAborted() }, { onPrepareRequested() }, { onPrepared() }, { onRenderingStart() }),
            ),
            Sequence(
                "paused seek keeps paused on buffering start",
                PlayState.PAUSED,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onBufferingStart() },
                ),
            ),
            Sequence(
                "paused seek keeps paused on buffering end",
                PlayState.PAUSED,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onBufferingEnd(true) },
                ),
            ),
            Sequence(
                "paused seek keeps paused on rendering start",
                PlayState.PAUSED,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onRenderingStart() },
                ),
            ),
            Sequence(
                "play request clears pause memory",
                PlayState.BUFFERING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onPlayRequested() },
                    { onBufferingStart() },
                ),
            ),
            Sequence(
                "stop request clears pause memory",
                PlayState.BUFFERING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onStopRequested() },
                    { onBufferingStart() },
                ),
            ),
            Sequence(
                "reset clears pause memory",
                PlayState.BUFFERING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onReset() },
                    { onBufferingStart() },
                ),
            ),
            Sequence(
                "error wins over pause memory",
                PlayState.ERROR,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onError() },
                ),
            ),
            Sequence(
                "content replacement clears pause memory",
                PlayState.PLAYING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onContentReplaced() },
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                ),
            ),
            Sequence(
                "prepare request clears pause memory",
                PlayState.PREPARING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { onPrepareRequested() },
                ),
            ),
            Sequence(
                "kernel alignment recovers playing",
                PlayState.PLAYING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onPauseRequested() },
                    { alignWithKernel(PlayState.PLAYING) },
                ),
            ),
            Sequence(
                "kernel alignment falls back to paused",
                PlayState.PAUSED,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { alignWithKernel(PlayState.PAUSED) },
                ),
            ),
            Sequence(
                "kernel alignment clears pause memory",
                PlayState.BUFFERING,
                listOf(
                    { onPrepareRequested() },
                    { onPrepared() },
                    { onRenderingStart() },
                    { onPauseRequested() },
                    { onSeekWhilePaused() },
                    { alignWithKernel(PlayState.PLAYING) },
                    { onBufferingStart() },
                ),
            ),
        )

        for (case in cases) {
            val machine = PlaybackStateMachine()
            for (step in case.steps) step(machine)
            assertEquals(case.name, case.expected, machine.currentState)
        }
    }

    @Test
    fun stateFlowExposesCurrentValue() {
        val machine = PlaybackStateMachine()

        machine.onPrepareRequested()
        machine.onPrepared()

        assertEquals(PlayState.PREPARED, machine.state.value)
    }

    @Test
    fun kernelAlignmentReportsOnlyRealChanges() {
        val machine = PlaybackStateMachine()
        machine.onPrepareRequested()
        machine.onPrepared()

        assertTrue(machine.alignWithKernel(PlayState.PLAYING))
        assertFalse(machine.alignWithKernel(PlayState.PLAYING))
        assertTrue(machine.alignWithKernel(PlayState.PAUSED))
        assertFalse(machine.alignWithKernel(PlayState.PAUSED))
    }
}
