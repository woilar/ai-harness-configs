# Thread Markdown verification

Run `scripts/build.py` then `scripts/test.py`, selecting the installed SDK with `IDEA_HOME`.
The headless contracts cover sanitization, all supported Markdown elements, code preservation,
unknown languages, malformed/nested fences, multiple blocks, bounded expansion, narrow layouts,
unchanged polling, replacement, reopening and the existing thread persistence/recovery behavior.

Native highlighting and editor disposal require a running IDE. `scripts/build-native-test.py`
produces a **separate test plugin**, excluded from the production ZIP. Install it alongside
OpenCode Review in a test IDE and invoke **Verify OpenCode Markdown** through Find Action.
It automatically checks native viewer/highlighter token styles, exact clipboard copying
(restoring the clipboard afterward), theme changes (restoring the original scheme), twenty
message replacements, and disposal of the actual editor objects it created. A disabled language
plugin is expected to fall back to PLAIN_TEXT. No project source files or review state are changed.

Results are written to `${java.io.tmpdir}/opencode-markdown-test/native-report.txt`; override the
directory with `-Dopencode.review.nativeTest=/absolute/test/path`. For automated startup, create
an empty `run` file in that directory before opening a project. The marker is consumed once.
Remove the test plugin after verification. A test window remains showing completion so the report
can be inspected; it is not part of the production UI. A passing report contains no FAIL entries
and ends with zero unreleased message editors. Counting *all* IDEA editors is incorrect because
project tabs may restore while the probe runs.

For transport verification use a disposable OpenCode session and local deterministic provider:
send a marked review turn, return fenced Markdown with `review_reply`, read it through `/threads`,
restart the user's own OpenCode service, reconnect the TUI to the same session and compare the
answer and answer ID byte-for-byte. Never use another user's server or real project code.

## Verified 2026-11-08

- 383 headless checks pass against IDEA 262.11879.100 and 263.7307.200.
- Native IDEA 262: Java (5 token styles), SQL (3), JSON (4), XML (4), plain text (1).
  Kotlin plugin is disabled in this installation; verified PLAIN_TEXT fallback, not Kotlin highlighting.
- Clipboard matches complete Java source including indentation and final newline.
- Light/dark editor-scheme switch passes and original scheme is restored.
- All 147 tracked message editors are disposed after 20 replacements and component removal.
- Real OpenCode 2.2.5 with a deterministic localhost provider: addressed Markdown arrives through
  review_reply and /threads; restarting the service and connecting a fresh TUI preserves answer,
  answer ID and exactly one turn. Native rendering and bridge reconnect were exercised separately;
  an end-to-end reconnect inside a persisted inline card was not automated.
- Complex list numbering around embedded native blocks may restart. Unknown/disabled language
  highlighters fall back to text; code lines use horizontal scrolling. Existing 20,000-character
  per-answer transport/storage limit is unchanged.
