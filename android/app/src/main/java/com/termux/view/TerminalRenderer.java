package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    // BATCH-G-STYLE (2026-09-10): VS Code-style visual link styling for plain-text
    // file paths. DEVICE PAINT FIXES D1/D2/D3 (advisor round 2026-10-19): the
    // token MATCHING now lives in the pure Kotlin object TerminalLinkPaint
    // (JVM-tested) — this renderer keeps only the paint geometry (plain-width
    // gate, cell-column walk, drawTextRun). Changes shipped with the move:
    //   D2: :line:col now anchors the paint (old pattern allowed one group).
    //   D1: quoted + shell-escaped spans painted WHOLE at detection time.
    //   D3: a token cut by the wrap is painted on BOTH visual rows (the
    //       wrapping row joins its tail with the continuation row's head and
    //       hands the segment to the next row via the seam carry).
    private static final int LINK_COLOR = 0xFF3794FF; // VS Code terminal link blue
    private final Paint mLinkPaint = new Paint();

    // D1 resolved-span overlay (advisor: "for others, show the resolved span
    // after resolution") — set by TerminalView before each render; pieces are
    // {row, cellStart, cellEnd} triplets in EXTERNAL row coordinates.
    private int[] mLinkOverlay;
    private long mLinkOverlayExpiresAt;

    public void setLinkOverlay(int[] pieces, long expiresAt) {
        mLinkOverlay = pieces;
        mLinkOverlayExpiresAt = expiresAt;
    }

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        mLinkPaint.setTypeface(typeface);
        mLinkPaint.setAntiAlias(true);
        mLinkPaint.setTextSize(textSize);
        mLinkPaint.setUnderlineText(true);
        mLinkPaint.setColor(LINK_COLOR);

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        // D3 seam carry: the segment a WRAPPING row's join matched into the
        // NEXT row (-1 = none). Reset automatically: paintForRow returns -1
        // whenever no seam match was produced for this row.
        int seamCarryIntoNextRow = -1;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            // D3 seam plumbing: does THIS row wrap into the next, and what is
            // the next row's text (only read when it wraps — cheap flag + a
            // buffer line that the next loop iteration reads anyway).
            final boolean rowWraps = screen.getLineWrap(row);
            char[] seamNextLine = null;
            int seamNextChars = 0;
            if (rowWraps && row + 1 < endRow) {
                TerminalRow seamNextObj = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row + 1));
                seamNextLine = seamNextObj.mText;
                seamNextChars = seamNextObj.getSpaceUsed();
            }

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);

            // BATCH-G-STYLE: redraw plain-text file-path tokens with link color + underline
            seamCarryIntoNextRow = drawFileLinkTokens(canvas, line, charsUsedInLine, heightOffset,
                rowWraps, seamNextLine, seamNextChars, seamCarryIntoNextRow, columns);
            // D1 resolved-span overlay: the tap resolver's winning span, mapped
            // to cells by TerminalLinkPaint.overlayCells (set from TerminalPane
            // after a successful resolve; TTL-checked here).
            if (mLinkOverlay != null && System.currentTimeMillis() < mLinkOverlayExpiresAt) {
                for (int p = 0; p + 2 < mLinkOverlay.length; p += 3) {
                    if (mLinkOverlay[p] != row) continue;
                    int oc1 = mLinkOverlay[p + 1];
                    int oc2 = mLinkOverlay[p + 2];
                    if (oc2 <= oc1 || oc1 >= columns) continue;
                    if (oc2 > columns) oc2 = columns;
                    int ocs = lineObject.findStartOfColumn(oc1);
                    int oce = (oc2 < columns) ? lineObject.findStartOfColumn(oc2) : charsUsedInLine;
                    if (oce > ocs) {
                        canvas.drawTextRun(line, ocs, oce - ocs, ocs, oce - ocs,
                            oc1 * mFontWidth, heightOffset - mFontLineSpacingAndAscent, false, mLinkPaint);
                    }
                }
            }
        }
    }


    /**
     * BATCH-G-STYLE: scans a rendered row for file-path tokens and redraws them with
     * link color + underline. Skips rows without '.' or '/' (fast exit), skips tokens
     * containing any char whose terminal cell width is not exactly 1 (surrogates,
     * wide CJK/emoji, combining marks) so the monospace geometry stays exact.
     */
    /**
     * BATCH-G-STYLE + DEVICE PAINT FIXES: the char ranges come from the pure
     * TerminalLinkPaint.paintForRow (D2 col groups, D1 quoted/escaped whole
     * spans, D3 wrap seam). Returns the seam carry for the NEXT row (>= 0:
     * paint its leading [0, carry) chars as link), or -1.
     */
    private int drawFileLinkTokens(Canvas canvas, char[] text, int charsUsed, float y,
                                   boolean rowWraps, char[] nextText, int nextChars,
                                   int carriedSeam, int columns) {
        if (charsUsed <= 0) return -1;
        String rowStr = new String(text, 0, charsUsed);
        String nextStr = (rowWraps && nextText != null) ? new String(nextText, 0, nextChars) : null;
        com.codespace.ide.terminal.TerminalLinkPaint.RowPaint paint =
            com.codespace.ide.terminal.TerminalLinkPaint.paintForRow(rowStr, carriedSeam, rowWraps, nextStr);
        for (com.codespace.ide.terminal.TerminalLinkPaint.Span span : paint.getSpans()) {
            int startIdx = span.getStart();
            int len = span.getEnd() - span.getStart();
            if (len <= 0 || startIdx >= charsUsed) continue;
            if (startIdx + len > charsUsed) len = charsUsed - startIdx;
            // All chars in the token must be plain width-1 chars (no surrogates/wide/combining)
            boolean plain = true;
            for (int i = 0; i < len; i++) {
                char c = text[startIdx + i];
                if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c) || WcWidth.width(c) != 1) {
                    plain = false;
                    break;
                }
            }
            if (!plain) continue;
            // Column offset of the token start = accumulated cell widths of the row prefix
            int col = 0;
            for (int i = 0; i < startIdx; i++) {
                char c = text[i];
                if (Character.isHighSurrogate(c)) {
                    int cp = Character.toCodePoint(c, text[i + 1]);
                    col += Math.max(WcWidth.width(cp), 0);
                    i++; // skip low surrogate
                } else {
                    int w = WcWidth.width(c);
                    if (w > 0) col += w;
                }
            }
            float left = col * mFontWidth;
            canvas.drawTextRun(text, startIdx, len, startIdx, len, left, y - mFontLineSpacingAndAscent, false, mLinkPaint);
        }
        return paint.getSeamIntoNext();
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
