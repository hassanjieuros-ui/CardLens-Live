package com.cardlens.live;

import org.junit.Test;

import static org.junit.Assert.*;

public class LanguageUtilTest {
    @Test public void detectsJapaneseCardText() {
        assertTrue(LanguageUtil.containsJapanese("ピカチュウ 025/165"));
        assertTrue(LanguageUtil.containsJapanese("リザードンex"));
    }

    @Test public void keepsEnglishSeparate() {
        assertFalse(LanguageUtil.containsJapanese("Pikachu 025/165"));
    }

    @Test public void normalizesJapaneseSearchText() {
        assertEquals("ピカチュウ025165", LanguageUtil.normalizeSearch("ピカチュウ 025/165"));
    }
}
