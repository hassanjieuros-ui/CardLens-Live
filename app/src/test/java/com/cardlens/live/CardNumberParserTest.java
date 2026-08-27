package com.cardlens.live;

import org.junit.Test;

import static org.junit.Assert.*;

public class CardNumberParserTest {
    @Test public void parsesModernCollectorFraction() {
        var c = CardNumberParser.parse("Illustrator\n148 / 142\nPokemon").orElseThrow();
        assertEquals("148", c.collectorNumber());
        assertEquals(142, c.printedTotal());
    }

    @Test public void parsesTrainerGalleryPrefix() {
        var c = CardNumberParser.parse("TG 10/30").orElseThrow();
        assertEquals("TG10", c.collectorNumber());
        assertEquals(30, c.printedTotal());
    }

    @Test public void ignoresUnrelatedSmallFractions() {
        assertTrue(CardNumberParser.parse("1/2 off today").isEmpty());
    }
}
