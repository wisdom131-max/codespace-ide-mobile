# P7.D - reading a reply aloud

Voice **input** already existed: `ChatVoice.rememberDictation` uses the platform recogniser through
`RecognizerIntent` with honest outcomes (Heard / Unavailable / Failed / Cancelled) and never invents a
transcript. Voice **output** did not exist. This is the output half.

## What was built

`ChatSpeech.kt`:

- **`SpeechState`**: Idle, Loading, Speaking, Unavailable(reason). A device with no speech engine reports
  Unavailable and says why; it never pretends to be speaking.
- **`Speaker`**: one reply at a time. `speak` flushes anything currently being said rather than queueing,
  so the user can always stop what they hear; `stop` halts immediately; `speak` on a failed engine is
  inert instead of throwing. The engine is shut down on dispose, with an explicit stop first, because a
  shutdown mid-utterance is the one case that throws on some OEM builds.
- **`SpeechText`** (pure, tested): fenced code is dropped (reading source aloud is noise), inline code is
  kept without its backticks (an identifier is worth hearing), headings/markers stripped, links spoken as
  their text not their URL, and a long answer cut at a sentence boundary with an explicit "... (the rest is
  in the chat.)" rather than stopping mid-word at a hard character limit.

## Wiring

`ChatOverflowAction.READ_REPLIES_ALOUD` appended **last** in the menu (after Approvals and Continue in New
Session), a checkbox, off by default, remembered in `ChatSettingsPrefs`. The panel speaks a reply when an
assistant row finishes streaming (`cursorOn == false`) and the toggle is on. Turning the toggle on when the
device cannot speak reports the reason in the notice line instead of promising sound.

Normal text chat is untouched: the composer, transcript and send path do not depend on the engine.

## Tests

`SpeechTextTest` (7): fenced code not spoken, inline code kept without backticks, a long answer bounded and
admitting truncation, a short answer spoken whole, nothing-to-say is nothing, links spoken as text, and a
speaker with no engine reports Unavailable even after `speak`.

`ChatOverflowSpecTest` updated honestly rather than deleted: the sixth label position is unchanged, the
item count is 8 because P7.D appended, and there are now two checkboxes (view-by-default and read-aloud)
which the test names explicitly.

`ConnectorApprovalTest` updated for P8.D: the old assertion that MCP is *not* forced encoded the behaviour
the owner's spec overrides. Local tools still keep level-based approval, and that part of the test is
unchanged.

Full suite 2592 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## P7 status

- A. Chat: message states, duplicate-reply prevention (`ea508c4`), error presentation, and continuation.
- B. Thinking Tree: planning / tool / progress / final are separated in the transcript.
- C. Tool interaction: activity rows, approval rows, partial-output labelling (`28553f4`).
- D. Voice: input (honest outcomes) and **output (this commit)**. Round-trip conversation (continuous
  listen-while-speaking) is **not** implemented; the two halves are independent, which is also what keeps
  text chat unaffected.
