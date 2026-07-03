#!/usr/bin/env python3
"""
test_f5_dictation.py — M9 Dictation Quality & Live Feedback tests.

Mirrors the pure logic of VoiceCommandProcessor.kt (and, in later commits,
VoiceActivityDetector.kt) in Python — same registry, same algorithm — so the
behaviour is validated offline without an Android build, following the
pcm16_to_float pattern in test_f2_audio.py. Structural token checks pin the
Kotlin implementation to the mirrored algorithm.
"""

import os
import re
import unittest

REPO = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))


def repo(*parts):
    return os.path.join(REPO, *parts)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


# ===========================================================================
# Python mirror of VoiceCommandProcessor.kt
# ===========================================================================

INSERT_TEXT = "insert"
DELETE_LAST_COMMIT = "delete_last_commit"

PUNCTUATION_COMMANDS = {
    ("period",): ".",
    ("full", "stop"): ".",
    ("comma",): ",",
    ("question", "mark"): "?",
    ("exclamation", "mark"): "!",
    ("exclamation", "point"): "!",
    ("colon",): ":",
    ("semicolon",): ";",
    ("new", "line"): "\n",
    ("new", "paragraph"): "\n\n",
}

ACTION_COMMANDS = {
    ("delete", "that"): DELETE_LAST_COMMIT,
    ("scratch", "that"): DELETE_LAST_COMMIT,
}

MAX_PHRASE_LENGTH = max(
    len(p) for p in list(PUNCTUATION_COMMANDS) + list(ACTION_COMMANDS)
)


def normalize_token(token: str) -> str:
    """Lowercase and strip surrounding punctuation (keeps inner apostrophes)."""
    return re.sub(r"^[^a-z0-9]+|[^a-z0-9]+$", "", token.lower())


def process(text: str):
    """Mirror of VoiceCommandProcessor.process — returns [(op, payload)]."""
    tokens = [t for t in text.strip().split() if t]
    ops = []
    builder = ""

    i = 0
    while i < len(tokens):
        consumed = 0
        for length in range(min(MAX_PHRASE_LENGTH, len(tokens) - i), 0, -1):
            normalized = tuple(normalize_token(tokens[i + k]) for k in range(length))
            if any(n == "" for n in normalized):
                continue
            if normalized in PUNCTUATION_COMMANDS:
                builder += PUNCTUATION_COMMANDS[normalized]
                consumed = length
                break
            if normalized in ACTION_COMMANDS:
                if builder:
                    builder = ""  # discard current-utterance text
                else:
                    ops.append((ACTION_COMMANDS[normalized], None))
                consumed = length
                break
        if consumed:
            i += consumed
        else:
            word = tokens[i]
            punctuation_only = normalize_token(word) == ""
            if builder and not builder.endswith("\n") and not punctuation_only:
                builder += " "
            builder += word
            i += 1

    if builder.strip():
        ops.append((INSERT_TEXT, builder))
    return ops


# ===========================================================================
# Tier 1 — happy paths
# ===========================================================================

class TestVoiceCommandsHappyPath(unittest.TestCase):

    def test_plain_text_passthrough(self):
        self.assertEqual(process("hello world"), [(INSERT_TEXT, "hello world")])

    def test_period_attaches_without_space(self):
        self.assertEqual(process("hello period"), [(INSERT_TEXT, "hello.")])

    def test_comma_mid_sentence(self):
        self.assertEqual(
            process("hello comma world"), [(INSERT_TEXT, "hello, world")]
        )

    def test_question_mark_two_word_phrase(self):
        self.assertEqual(process("really question mark"), [(INSERT_TEXT, "really?")])

    def test_exclamation_both_variants(self):
        self.assertEqual(process("wow exclamation mark"), [(INSERT_TEXT, "wow!")])
        self.assertEqual(process("wow exclamation point"), [(INSERT_TEXT, "wow!")])

    def test_full_stop_alias(self):
        self.assertEqual(process("done full stop"), [(INSERT_TEXT, "done.")])

    def test_colon_and_semicolon(self):
        self.assertEqual(
            process("note colon buy milk semicolon eggs"),
            [(INSERT_TEXT, "note: buy milk; eggs")],
        )

    def test_new_line_no_space_after(self):
        self.assertEqual(
            process("first new line second"), [(INSERT_TEXT, "first\nsecond")]
        )

    def test_new_paragraph(self):
        self.assertEqual(
            process("intro new paragraph body"), [(INSERT_TEXT, "intro\n\nbody")]
        )

    def test_delete_that_alone_targets_previous_commit(self):
        self.assertEqual(process("delete that"), [(DELETE_LAST_COMMIT, None)])

    def test_scratch_that_alias(self):
        self.assertEqual(process("scratch that"), [(DELETE_LAST_COMMIT, None)])

    def test_delete_that_discards_current_utterance(self):
        self.assertEqual(
            process("hello world delete that"), []
        )

    def test_delete_that_then_continue(self):
        self.assertEqual(
            process("hello delete that goodbye"), [(INSERT_TEXT, "goodbye")]
        )

    def test_delete_previous_then_dictate(self):
        self.assertEqual(
            process("delete that hello"),
            [(DELETE_LAST_COMMIT, None), (INSERT_TEXT, "hello")],
        )


# ===========================================================================
# Tier 2 — boundary and Whisper-artifact tolerance
# ===========================================================================

class TestVoiceCommandsBoundary(unittest.TestCase):

    def test_empty_string(self):
        self.assertEqual(process(""), [])

    def test_whitespace_only(self):
        self.assertEqual(process("   \n  "), [])

    def test_case_insensitive_match(self):
        self.assertEqual(process("Hello Period"), [(INSERT_TEXT, "Hello.")])

    def test_whisper_trailing_punctuation_on_command(self):
        # Whisper often emits "New line." with its own period attached.
        self.assertEqual(
            process("first New line. second"), [(INSERT_TEXT, "first\nsecond")]
        )

    def test_whisper_capitalized_delete_that(self):
        self.assertEqual(process("Delete that."), [(DELETE_LAST_COMMIT, None)])

    def test_non_command_words_keep_original_case(self):
        self.assertEqual(
            process("Meet Bob comma OK"), [(INSERT_TEXT, "Meet Bob, OK")]
        )

    def test_partial_phrase_is_literal(self):
        # "question" alone is not a command
        self.assertEqual(
            process("good question here"), [(INSERT_TEXT, "good question here")]
        )

    def test_phrase_split_across_non_adjacent_words_is_literal(self):
        self.assertEqual(
            process("new big line"), [(INSERT_TEXT, "new big line")]
        )

    def test_multiple_commands_chained(self):
        self.assertEqual(
            process("one period new line two question mark"),
            [(INSERT_TEXT, "one.\ntwo?")],
        )

    def test_command_at_start_of_utterance(self):
        self.assertEqual(process("period hello"), [(INSERT_TEXT, ". hello")])

    def test_inner_apostrophe_preserved(self):
        self.assertEqual(process("don't stop"), [(INSERT_TEXT, "don't stop")])

    def test_punctuation_only_token_attaches_without_space(self):
        self.assertEqual(process("hello ."), [(INSERT_TEXT, "hello.")])


# ===========================================================================
# Structural checks — pin Kotlin implementation to the mirrored algorithm
# ===========================================================================

class TestVoiceCommandProcessorStructure(unittest.TestCase):

    def _kt(self):
        return read(
            repo("app/src/main/java/com/openfree/client/VoiceCommandProcessor.kt")
        )

    def test_kotlin_file_exists(self):
        self.assertTrue(
            os.path.isfile(
                repo("app/src/main/java/com/openfree/client/VoiceCommandProcessor.kt")
            )
        )

    def test_kotlin_has_text_op_types(self):
        kt = self._kt()
        self.assertIn("sealed class TextOp", kt)
        self.assertIn("InsertText", kt)
        self.assertIn("DeleteLastCommit", kt)

    def test_kotlin_has_process_function(self):
        self.assertIn("fun process", self._kt())

    def test_kotlin_registry_matches_python_mirror(self):
        """Every phrase in the Python mirror must appear in the Kotlin registry."""
        kt = self._kt()
        for phrase_tokens in list(PUNCTUATION_COMMANDS) + list(ACTION_COMMANDS):
            phrase = " ".join(phrase_tokens)
            self.assertIn(
                f'"{phrase}"', kt,
                f"Kotlin registry is missing command phrase: {phrase!r}"
            )

    def test_kotlin_registry_has_no_extra_commands(self):
        """Every quoted phrase in the Kotlin registries must exist in the mirror."""
        kt = self._kt()
        registry_src = kt[kt.index("punctuationCommands"):kt.index("maxPhraseLength")]
        known = {" ".join(p) for p in list(PUNCTUATION_COMMANDS) + list(ACTION_COMMANDS)}
        known_literals = set(PUNCTUATION_COMMANDS.values())
        for match in re.findall(r'"((?:[^"\\]|\\.)*)"', registry_src):
            literal = match.replace("\\n", "\n")
            if literal.strip() == "":
                continue  # the " " delimiter passed to split(" ")
            self.assertTrue(
                literal in known or literal in known_literals,
                f"Kotlin registry phrase {match!r} missing from Python mirror — "
                f"update test_f5_dictation.py to keep behaviours in sync"
            )


# ===========================================================================
# Python mirror of VoiceActivityDetector.kt
# ===========================================================================

FRAME_SIZE = 480          # 30 ms at 16 kHz
FRAME_MS = 30
DEFAULT_SPEECH_THRESHOLD = 0.015
TRIM_PADDING_MS = 200
SAMPLE_RATE = 16000
PADDING_SAMPLES = SAMPLE_RATE * TRIM_PADDING_MS // 1000


def frame_rms(samples, length, offset=0):
    if length <= 0:
        return 0.0
    total = sum(s * s for s in samples[offset:offset + length])
    return (total / length) ** 0.5


class MirrorVad:
    """Mirror of VoiceActivityDetector: frame RMS, silence timeout latch."""

    def __init__(self, silence_timeout_ms, speech_threshold=DEFAULT_SPEECH_THRESHOLD):
        self.silence_timeout_ms = silence_timeout_ms
        self.speech_threshold = speech_threshold
        self.reset()

    def reset(self):
        self.remainder = []
        self.has_heard_speech = False
        self.silence_ms = 0
        self.fired = False

    def process_chunk(self, chunk):
        if self.silence_timeout_ms <= 0 or self.fired:
            return False
        self.remainder.extend(chunk)
        while len(self.remainder) >= FRAME_SIZE:
            frame = self.remainder[:FRAME_SIZE]
            del self.remainder[:FRAME_SIZE]
            if frame_rms(frame, FRAME_SIZE) >= self.speech_threshold:
                self.has_heard_speech = True
                self.silence_ms = 0
            elif self.has_heard_speech:
                self.silence_ms += FRAME_MS
                if self.silence_ms >= self.silence_timeout_ms:
                    self.fired = True
                    return True
        return False


def trim_silence(samples, threshold=DEFAULT_SPEECH_THRESHOLD):
    """Mirror of VoiceActivityDetector.trimSilence."""
    if not samples:
        return samples
    first_speech = -1
    last_speech = -1
    frame = 0
    offset = 0
    while offset < len(samples):
        length = min(FRAME_SIZE, len(samples) - offset)
        if frame_rms(samples, length, offset) >= threshold:
            if first_speech < 0:
                first_speech = frame
            last_speech = frame
        frame += 1
        offset += FRAME_SIZE
    if first_speech < 0:
        return []
    start = max(0, first_speech * FRAME_SIZE - PADDING_SAMPLES)
    end = min(len(samples), (last_speech + 1) * FRAME_SIZE + PADDING_SAMPLES)
    return samples[start:end]


def speech_frames(n):
    """n frames of constant 0.1 amplitude (RMS 0.1 — well above threshold)."""
    return [0.1] * (FRAME_SIZE * n)


def silent_frames(n):
    return [0.0] * (FRAME_SIZE * n)


# ===========================================================================
# VAD — auto-stop behaviour
# ===========================================================================

class TestVadAutoStop(unittest.TestCase):

    def test_pure_silence_never_fires(self):
        vad = MirrorVad(300)
        self.assertFalse(vad.process_chunk(silent_frames(100)))

    def test_fires_after_silence_timeout_following_speech(self):
        vad = MirrorVad(300)  # 10 frames of 30 ms
        self.assertFalse(vad.process_chunk(speech_frames(5)))
        self.assertFalse(vad.process_chunk(silent_frames(9)))
        self.assertTrue(vad.process_chunk(silent_frames(1)))

    def test_speech_resets_silence_counter(self):
        vad = MirrorVad(300)
        vad.process_chunk(speech_frames(2))
        self.assertFalse(vad.process_chunk(silent_frames(9)))
        vad.process_chunk(speech_frames(1))  # counter resets
        self.assertFalse(vad.process_chunk(silent_frames(9)))
        self.assertTrue(vad.process_chunk(silent_frames(1)))

    def test_fires_exactly_once(self):
        vad = MirrorVad(300)
        vad.process_chunk(speech_frames(1))
        self.assertTrue(vad.process_chunk(silent_frames(10)))
        self.assertFalse(vad.process_chunk(silent_frames(50)))

    def test_disabled_when_timeout_zero(self):
        vad = MirrorVad(0)
        vad.process_chunk(speech_frames(1))
        self.assertFalse(vad.process_chunk(silent_frames(1000)))

    def test_chunks_smaller_than_frame_accumulate(self):
        vad = MirrorVad(60)  # 2 frames
        vad.process_chunk(speech_frames(1))
        fired = False
        # feed 3 frames of silence in 10-sample dribbles
        silence = silent_frames(3)
        for i in range(0, len(silence), 10):
            if vad.process_chunk(silence[i:i + 10]):
                fired = True
        self.assertTrue(fired)

    def test_reset_clears_state(self):
        vad = MirrorVad(300)
        vad.process_chunk(speech_frames(1))
        vad.process_chunk(silent_frames(10))
        vad.reset()
        self.assertFalse(vad.process_chunk(silent_frames(100)))


# ===========================================================================
# VAD — silence trimming
# ===========================================================================

class TestVadTrimming(unittest.TestCase):

    def test_empty_input(self):
        self.assertEqual(trim_silence([]), [])

    def test_all_silence_returns_empty(self):
        self.assertEqual(trim_silence(silent_frames(20)), [])

    def test_all_speech_unchanged(self):
        samples = speech_frames(10)
        self.assertEqual(trim_silence(samples), samples)

    def test_leading_and_trailing_silence_trimmed_with_padding(self):
        lead = silent_frames(50)    # 24000 samples
        speech = speech_frames(10)  # 4800 samples
        tail = silent_frames(50)
        trimmed = trim_silence(lead + speech + tail)
        # speech spans frames [50, 59] -> samples [24000, 28800) +/- padding
        expected_len = len(speech) + 2 * PADDING_SAMPLES
        self.assertEqual(len(trimmed), expected_len)
        # padded regions are silence, core is intact
        self.assertEqual(trimmed[PADDING_SAMPLES:PADDING_SAMPLES + len(speech)], speech)

    def test_padding_clamped_at_boundaries(self):
        # speech starts at sample 0 — no negative start index
        samples = speech_frames(2) + silent_frames(50)
        trimmed = trim_silence(samples)
        self.assertEqual(len(trimmed), len(speech_frames(2)) + PADDING_SAMPLES)

    def test_short_final_partial_frame_handled(self):
        # length not a multiple of FRAME_SIZE must not crash
        samples = silent_frames(2) + speech_frames(1) + [0.1] * 100
        trimmed = trim_silence(samples)
        self.assertGreater(len(trimmed), 0)


# ===========================================================================
# Structural checks — VAD + AudioRecorder streaming
# ===========================================================================

class TestVadStructure(unittest.TestCase):

    def _kt(self):
        return read(
            repo("app/src/main/java/com/openfree/client/VoiceActivityDetector.kt")
        )

    def test_kotlin_file_exists(self):
        self.assertTrue(
            os.path.isfile(
                repo("app/src/main/java/com/openfree/client/VoiceActivityDetector.kt")
            )
        )

    def test_kotlin_constants_match_mirror(self):
        kt = self._kt()
        self.assertIn("FRAME_SIZE = 480", kt)
        self.assertIn("FRAME_MS = 30", kt)
        self.assertIn("DEFAULT_SPEECH_THRESHOLD = 0.015f", kt)
        self.assertIn("TRIM_PADDING_MS = 200", kt)

    def test_kotlin_has_api_surface(self):
        kt = self._kt()
        self.assertIn("fun processChunk", kt)
        self.assertIn("fun trimSilence", kt)
        self.assertIn("fun reset", kt)

    def test_audio_recorder_has_live_chunk_callback(self):
        kt = read(repo("app/src/main/java/com/openfree/client/AudioRecorder.kt"))
        self.assertIn("onChunk", kt)


# ===========================================================================
# Structural checks — StreamingTranscriber + surface wiring
# ===========================================================================

class TestStreamingTranscriberStructure(unittest.TestCase):

    def _kt(self):
        return read(
            repo("app/src/main/java/com/openfree/client/StreamingTranscriber.kt")
        )

    def test_kotlin_file_exists(self):
        self.assertTrue(
            os.path.isfile(
                repo("app/src/main/java/com/openfree/client/StreamingTranscriber.kt")
            )
        )

    def test_session_api_surface(self):
        kt = self._kt()
        for token in ("fun startSession", "fun feedChunk", "fun finishSession",
                      "fun cancelSession", "fun onPartial", "fun onSilenceTimeout"):
            self.assertIn(token, kt)

    def test_final_pass_trims_silence(self):
        self.assertIn("trimSilence", self._kt())

    def test_config_reads_shared_pref_keys(self):
        kt = self._kt()
        self.assertIn("KEY_LIVE_PREVIEW", kt)
        self.assertIn("KEY_VAD_AUTO_STOP_SECONDS", kt)
        self.assertIn("KEY_VOICE_COMMANDS", kt)

    def test_pref_keys_defined_in_ime(self):
        kt = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        self.assertIn('"pref_key_live_preview"', kt)
        self.assertIn('"pref_key_vad_auto_stop_seconds"', kt)
        self.assertIn('"pref_key_voice_commands"', kt)

    def test_whisper_engine_serialises_native_access(self):
        kt = read(repo("app/src/main/java/com/openfree/client/WhisperEngine.kt"))
        annotations = re.findall(r"^\s*@Synchronized\s*$", kt, re.MULTILINE)
        self.assertEqual(
            len(annotations), 3,
            "loadModel/transcribe/unloadModel must all be @Synchronized"
        )


class TestSurfaceWiring(unittest.TestCase):

    def _ime(self):
        return read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))

    def _floating(self):
        return read(
            repo("app/src/main/java/com/openfree/client/FloatingOpenFreeService.kt")
        )

    def test_ime_uses_streaming_session(self):
        kt = self._ime()
        for token in ("streamingTranscriber.startSession", "feedChunk",
                      "streamingTranscriber.finishSession",
                      "streamingTranscriber.cancelSession"):
            self.assertIn(token, kt)

    def test_floating_uses_streaming_session(self):
        kt = self._floating()
        for token in ("streamingTranscriber.startSession", "feedChunk",
                      "streamingTranscriber.finishSession",
                      "streamingTranscriber.cancelSession"):
            self.assertIn(token, kt)

    def test_both_surfaces_execute_voice_command_ops(self):
        self.assertIn("VoiceCommandProcessor.process", self._ime())
        self.assertIn("VoiceCommandProcessor.process", self._floating())
        self.assertIn("DeleteLastCommit", self._ime())
        self.assertIn("DeleteLastCommit", self._floating())

    def test_ime_shows_partials_in_preview(self):
        kt = self._ime()
        self.assertIn("onPartial", kt)
        self.assertIn("txtPreview?.text = text", kt)

    def test_floating_layout_has_partial_text_view(self):
        layout = read(repo("app/src/main/res/layout/floating_layout.xml"))
        self.assertIn("tv_partial", layout)

    def test_both_surfaces_wire_vad_auto_stop(self):
        self.assertIn("onSilenceTimeout", self._ime())
        self.assertIn("onSilenceTimeout", self._floating())


if __name__ == "__main__":
    unittest.main()
