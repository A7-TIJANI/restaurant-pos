package com.epicit.pos.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-6.1: "next unused palette slot", never {@code menu.length % N} (v1 bug, spec 3.2 #10). */
class MenuPaletteTest {

    private static final List<String> C = MenuPalette.COLORS;

    @Test
    void theV1PaletteHasThirteenDistinctColours() {
        assertThat(C).hasSize(13).doesNotHaveDuplicates().allMatch(c -> c.matches("#[0-9a-f]{6}"));
    }

    @Test
    void aFreshMenuStartsAtTheFirstColour() {
        assertThat(MenuPalette.next(List.of())).isEqualTo(C.get(0));
    }

    @Test
    void theThreeSeededItemsLeaveTheFourthColourNext() {
        assertThat(MenuPalette.next(List.of(C.get(0), C.get(1), C.get(2)))).isEqualTo(C.get(3));
    }

    @Test
    void aFreedColourIsReusedBeforeAnyNewOne() {
        // v1 bug: delete the 2nd of 4 items and "length % N" hands out colour #3 again (a duplicate)
        assertThat(MenuPalette.next(List.of(C.get(0), C.get(2), C.get(3)))).isEqualTo(C.get(1));
    }

    @Test
    void whenAllThirteenAreTakenTheLeastUsedWinsAndTiesGoToTheEarliest() {
        assertThat(MenuPalette.next(C)).isEqualTo(C.get(0));
        List<String> fourteen = new java.util.ArrayList<>(C);
        fourteen.add(C.get(0));
        assertThat(MenuPalette.next(fourteen)).isEqualTo(C.get(1));
    }

    @Test
    void unknownColoursAreIgnoredWhenCounting() {
        assertThat(MenuPalette.next(List.of("#123456", "nonsense", C.get(0)))).isEqualTo(C.get(1));
    }

    @Test
    void canonicalAcceptsOnlyPaletteColoursInAnyCase() {
        assertThat(MenuPalette.canonical("#E74C3C")).isEqualTo("#e74c3c");
        assertThat(MenuPalette.canonical(" #27ae60 ")).isEqualTo("#27ae60");
        assertThat(MenuPalette.canonical("#ffffff")).isNull();
        assertThat(MenuPalette.canonical("red")).isNull();
        assertThat(MenuPalette.canonical(null)).isNull();
    }
}
