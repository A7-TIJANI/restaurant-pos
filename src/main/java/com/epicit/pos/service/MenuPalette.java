package com.epicit.pos.service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * FR-6.1: v1's colour palette, handed out by "next unused slot". v1 used {@code menu.length % N}, which after a
 * deletion produced duplicate and skipped colours (spec section 3.2 #10). Here a colour counts as used only while
 * an <em>active</em> item wears it, so deactivating an item frees its colour again.
 */
public final class MenuPalette {

    /** The v1 palette, in v1's order. The three seeded items already use the first three. */
    public static final List<String> COLORS = List.of(
            "#e74c3c", "#27ae60", "#2980b9", "#f1c40f", "#8e44ad",
            "#e67e22", "#16a085", "#2c3e50", "#d35400", "#c0392b",
            "#34495e", "#7f8c8d", "#2ecc71");

    private MenuPalette() {
    }

    /**
     * The first palette colour no active item uses. If every colour is taken (more than 13 active items), the
     * least-used colour, earliest in the palette on a tie, so colours repeat as evenly as possible.
     */
    public static String next(Collection<String> colorsOfActiveItems) {
        int[] uses = new int[COLORS.size()];
        for (String c : colorsOfActiveItems) {
            int i = indexOf(c);
            if (i >= 0) {
                uses[i]++;
            }
        }
        int best = 0;
        for (int i = 0; i < uses.length; i++) {
            if (uses[i] < uses[best]) {
                best = i;
            }
        }
        return COLORS.get(best);
    }

    /** The palette's own spelling of {@code color} (case-insensitive), or null if it is not a palette colour. */
    public static String canonical(String color) {
        int i = indexOf(color);
        return i < 0 ? null : COLORS.get(i);
    }

    private static int indexOf(String color) {
        if (color == null) {
            return -1;
        }
        String c = color.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < COLORS.size(); i++) {
            if (COLORS.get(i).equals(c)) {
                return i;
            }
        }
        return -1;
    }
}
