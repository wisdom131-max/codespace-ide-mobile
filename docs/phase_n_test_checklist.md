# Phase N — Advanced Notification System: Test Checklist

> Run all tests on a physical device. Mark ✅ or ❌ for each.

## Phase 1-7: Core Model & UI

### N-01: Basic notification add
- [ ] Open app → no notifications → bell shows "idle" (gray)
- [ ] Trigger an LSP event (open a .kt file) → notification appears in drawer
- [ ] Bell changes to "info" (blue) for info notifications

### N-02: Priority levels
- [ ] LOW priority notification appears but does not trigger toast
- [ ] NORMAL priority triggers toast banner
- [ ] HIGH priority triggers toast + sound (if enabled)
- [ ] CRITICAL priority triggers toast + sound + persists until dismissed

### N-03: Severity types
- [ ] INFO → blue bell
- [ ] SUCCESS → green notification in drawer
- [ ] WARNING → amber bell
- [ ] ERROR → red bell
- [ ] PROGRESS → shows progress bar in toast and drawer

### N-04: Notification states
- [ ] ACTIVE → unread, bold in drawer
- [ ] READ → marked read on tap, dimmed
- [ ] DISMISSED → removed from list via swipe/dismiss
- [ ] COMPLETED → progress notification auto-completes
- [ ] FAILED → error notification with red indicator

### N-05: Action buttons
- [ ] LSP crash notification shows [View Logs] and [Restart] buttons
- [ ] Tapping [View Logs] opens the problems panel
- [ ] Tapping [Restart] restarts the LSP
- [ ] Build failure shows [View Logs] button
- [ ] Action button tap dismisses notification (if destructive)

### N-06: Progress notifications
- [ ] Build start shows indeterminate progress bar
- [ ] Progress updates update the bar in-place (no new notification)
- [ ] Build complete → progress notification replaced with success
- [ ] Build failure → progress notification replaced with error

### N-07: Error details
- [ ] Error notification shows "tap for details" hint
- [ ] Tapping error notification expands technical details
- [ ] Technical details show command, exit code, stderr (no secrets)
- [ ] Tapping again collapses details

## Phase 8: Undo

### N-08: Undo dismiss
- [ ] Dismiss a notification via dismissWithUndo()
- [ ] Tap undo button (refresh icon) in drawer header
- [ ] Notification reappears at top of list
- [ ] Undo stack max 10 — after 10 dismisses, oldest is lost

### N-09: Undo clear all
- [ ] Clear all notifications
- [ ] Tap undo button
- [ ] Up to 10 most recent notifications restored

## Phase 9: Persistence

### N-10: History survives restart
- [ ] Add several notifications
- [ ] Kill app (force stop)
- [ ] Reopen app
- [ ] Last 50 notifications restored in drawer
- [ ] IDs are sequential (no collision with restored IDs)

## Phase 10: Settings

### N-11: Per-source toggle
- [ ] Open drawer → tap DND icon → toggle a source filter
- [ ] Notifications from that source are hidden
- [ ] Toggle back → notifications reappear
- [ ] Setting persists across restart

### N-12: Per-severity toggle
- [ ] Toggle off INFO severity
- [ ] INFO notifications no longer appear
- [ ] ERROR/WARNING still appear
- [ ] Setting persists across restart

### N-13: Max history
- [ ] Set max history to 10
- [ ] Add 15 notifications
- [ ] Only 10 most recent remain
- [ ] Setting persists

## Phase 11: Build Integration

### N-14: Build lifecycle
- [ ] Start build → "Build started" notification with progress
- [ ] Build succeeds → "Build successful" with error/warning counts
- [ ] Build fails → "Build failed" with [View Logs] action
- [ ] Build cancelled → "Build cancelled" notification
- [ ] Build error (exception) → "Build error" notification

## Phase 12: Debug Integration

### N-15: Debug session lifecycle
- [ ] Start debug session → no notification (not wired for start)
- [ ] Stop debug session → "Debug session ended" notification
- [ ] Notification shows language and session ID

## Phase 13: Terminal Integration

### N-16: Terminal session lifecycle
- [ ] Start terminal → no notification
- [ ] Exit terminal normally (exit 0) → "Terminal session ended"
- [ ] Crash terminal (exit non-zero) → "Terminal session crashed" with error styling

## Phase 14: Accessibility

### N-17: Screen reader
- [ ] Enable TalkBack
- [ ] Navigate to notification row
- [ ] Screen reader reads: severity, title, body, dedup count, actions
- [ ] Action buttons are announced

## Phase 15: Rate Limiting

### N-18: Deduplication
- [ ] Trigger 10 LSP errors rapidly → only 1 notification with count "(10)"
- [ ] 5 second window → new notification after window expires

### N-19: Per-source rate limit
- [ ] Trigger 15 LSP events in 1s → only 10 appear (rate limit = 10)
- [ ] Trigger 5 build events in 1s → only 3 appear (rate limit = 3)
- [ ] Excess notifications update existing (dedupCount++)

### N-20: Burst protection
- [ ] Trigger 60 notifications across all sources in 10s
- [ ] After 50, notifications are suppressed
- [ ] suppressedCount increments
- [ ] After 10s window, notifications resume

## Cross-cutting

### N-21: Do Not Disturb
- [ ] Enable DND
- [ ] INFO/WARNING/SUCCESS notifications suppressed in toast
- [ ] ERROR notifications still show (override DND)
- [ ] Drawer still shows all notifications
- [ ] Disable DND → all notifications show in toast again

### N-22: Sound
- [ ] Enable sound → notification plays system sound
- [ ] Disable sound → no sound
- [ ] DND suppresses sound for non-error notifications

### N-23: Bell position
- [ ] Change bell to bottom-left → bell moves
- [ ] Change bell to top-right → bell moves
- [ ] Change bell to bottom-right → bell moves
- [ ] Position persists across restart

### N-24: clearResolved()
- [ ] Have mix of ACTIVE, COMPLETED, FAILED, DISMISSED items
- [ ] Call clearResolved()
- [ ] Only ACTIVE items remain
