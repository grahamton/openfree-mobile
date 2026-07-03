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


if __name__ == "__main__":
    unittest.main()
