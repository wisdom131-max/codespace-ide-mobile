# Chat parity: read from VS Code source, not memory

Read 2026-11-01 from microsoft/vscode `main` (raw source via the GitHub contents API).
Paths are repo-relative under `src/vs/workbench/contrib/chat/`.

## 1. Auto-scroll (chatWidget.ts)

- `:1122` `const isAtBottom = this.listWidget.isScrolledToBottom;`
- `:1126` `scrollAnchor: isAtBottom ? undefined : this.listWidget.getScrollAnchor(),`
- `:1131` `if (state.isAtBottom) { ... }` — the anchor is only restored when the view was
  pinned to the bottom; otherwise the exact element under the viewport is restored.
- `:4529` `if (lastElementVisible && !this.listWidget.isAutoScrollHeld && ...)` — new content
  follows only while the last element is visible AND auto-scroll has not been held.
- `:3917` `this.listWidget.setScrollLock(...)`.

So the rule is: pinned-to-bottom, an explicit "held" state that user dragging sets, and an
element anchor restored when content above the viewport grows. Our `AutoScrollPolicy` +
`FollowFlag` implement the first two exactly (slop in px, user-only movement changes the flag).
The part we do NOT have is the anchor restore: we only ever scroll to `maxValue`, so a section
re-rendering above the viewport can still shift what the user is reading. That is the one
remaining jitter source.

## 2. Hidden reasoning (common/constants.ts:365)

```ts
export enum ThinkingDisplayMode {
	Collapsed = 'collapsed',
	CollapsedPreview = 'collapsedPreview',
	FixedScrolling = 'fixedScrolling',
}
```

Three disclosure modes, all of them collapsed by default with a preview line; the model's raw
reasoning is never dumped into the transcript. `FixedScrolling` is the expanded state with its
own scroll area. That is the render we are missing: ours shows a bare `Thinking (n)` row with no
chevron, no preview text and no expanded view.

## 3. Rate limiting and trimming (chatWidget.ts)

Searching the widget for `ratelimit`, `429`, `retry after` and `truncat` returns exactly one
row: `:3969`, a comment about an agent-host protocol truncation action. There is no client-side
request limiter, no response trimmer and no history-dropping logic in the chat widget at all.

Conclusion for ours: `ChatRowModel.RateLimited` and `ChatRowModel.TrimmedHistory` are our own
inventions, and `TrimmedHistory` is the one that cuts answers short. VS Code's answer to
provider limits is a retry that preserves what has already streamed, plus provider-side context
management that the user never sees as a truncated reply.

## 4. Attachments (browser/attachments/chatAttachmentWidgets.ts)

- `:500` every attachment carries `mimeType: string` — the type is a property of the attachment,
  resolved before the widget is built, not something the widget tolerates being absent.
- `:1368` `switch (attachment.mimeType)` chooses the widget (image, PDF, text, resource).
- `:684` images render as `data:${imageData.mimeType};base64,...`.
- `:1445` outputs are matched back by mime.

Nothing in this path can produce "could not determine its type": when the picker cannot answer,
the name answers. That is the fix shipped in `ChatSession.mimeForName` (see
`AttachmentTypingTest`), and it is why chips were being refused for `.md` and `.kt`.

Pills live inside the input box with a remove action (`browser/chatInputPills.ts`,
`browser/attachments/chatAttachmentWidgets.ts`), which matches the observed VS Code behaviour:
a chip at the head of the field with a small x, removable before sending.
