package com.cardlens.live;

import org.junit.Test;

import static org.junit.Assert.*;

public class CardNumberParserTest {
    @Test public void parsesModernCollectorFraction() {
        var c = CardNumberParser.parse("Illustrator\n148 / 142\nPokemon").orElseThrow();
        assertEquals("148", c.collectorNumber());
        assertEquals(142, c.printedTotal());
        assertFalse(c.isNameCandidate());
    }

    @Test public void parsesTrainerGalleryPrefix() {
        var c = CardNumberParser.parse("TG 10/30").orElseThrow();
        assertEquals("TG10", c.collectorNumber());
        assertEquals(30, c.printedTotal());
    }

    @Test public void fallsBackToEnglishNameWhenNumberIsTiny() {
        var c = CardNumberParser.parse("STAGE 1  Rabsca  70HP\nRevival Blessing\nPsybeam 50")
                .orElseThrow();
        assertTrue(c.isNameCandidate());
        assertEquals("Rabsca", c.nameHint());
    }

    @Test public void fallsBackToJapaneseName() {
        var c = CardNumberParser.parse("たね ピカチュウ HP70\nでんきショック")
                .orElseThrow();
        assertTrue(c.isNameCandidate());
        assertTrue(c.nameHint().contains("ピカチュウ"));
    }

    @Test public void ignoresUnrelatedSmallFractions() {
        assertTrue(CardNumberParser.parse("1/2 off today").isEmpty());
    }

    @Test public void rejectsImplausibleOcrRatio() {
        assertTrue(CardNumberParser.parse("noise 111/10 more noise").isEmpty());
    }

    @Test public void stillAllowsSecretRareAbovePrintedTotal() {
        var c = CardNumberParser.parse("205/165").orElseThrow();
        assertEquals("205", c.collectorNumber());
        assertEquals(165, c.printedTotal());
    }
}
