package com.gathin.tvhome;

/** Physical left/right boundaries for a grid whose first column follows its reading direction. */
final class GridNavigation {
    private GridNavigation() { }

    static boolean atHorizontalEdge(int position, int count, int columns, boolean rtl, boolean left) {
        boolean towardStart = left != rtl;
        if (towardStart) return position % columns == 0;
        return position % columns == columns - 1 || position == count - 1;
    }

    /** Dim level of a non-carried tile: edit-dock dims only the other dock places, Move mode dims every tile. */
    static float idleAlpha(int pos, int columns, boolean dockEditing, int dockEditSlot, boolean reordering) {
        if (dockEditing) return dockEditSlot >= 0 && pos >= 0 && pos < columns && pos != dockEditSlot ? 0.45f : 1f;
        return reordering ? 0.45f : 1f;
    }

    /** spotlight_focus: the other tiles of the focused row. */
    static final float SPOTLIGHT_ROW_ALPHA = 0.55f;
    /** peek_row: tiles in a row without focus. */
    static final float PEEK_ROW_ALPHA = 0.40f;
    /** peek_row: the rows under the dock are drawn at this size. */
    static final float PEEK_SCALE = 0.86f;

    /**
     * spotlight_focus: the focused tile at full strength, the rest of its row at 55 %, and with peek_row the other
     * rows at 40 %. focused -1 (focus on the gear) counts the dock as the row in front, with no tile lit.
     */
    static float spotlightAlpha(int pos, int focused, int columns, boolean peek) {
        if (pos == focused && pos >= 0) return 1f;
        int frontRow = focused < 0 ? 0 : focused / columns;
        boolean frontRowTile = pos >= 0 && pos / columns == frontRow;
        return frontRowTile || !peek ? SPOTLIGHT_ROW_ALPHA : PEEK_ROW_ALPHA;
    }

    /**
     * peek_row: how far a smaller row's tile moves sideways, so the row shrinks around the screen's middle rather
     * than each tile in its own column. The tile is scaled about its centre.
     */
    static float peekShiftX(int col, int columns, float cellPx, float scale) {
        return (col - (columns - 1) / 2f) * cellPx * (scale - 1f);
    }

    /**
     * peek_row: how far the smaller rows move up so the first of them, scaled about the tile's centre, starts gapPx
     * under the dock's tiles. pitchPx is the layout distance from a dock tile's top to the next row's tile top.
     */
    static float peekShiftY(float tileHeightPx, float pitchPx, float gapPx, float scale) {
        return tileHeightPx + gapPx - pitchPx - tileHeightPx * (1f - scale) / 2f;
    }
}
