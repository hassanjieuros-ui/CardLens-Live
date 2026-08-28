package com.cardlens.live;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EvidenceWindowTest {
    @Test
    public void fusesTextAcrossNearbyFrames() {
        EvidenceWindow window = new EvidenceWindow();
        long t = 1000;
        window.record("", "Tapu Bulu", .55, t);
        window.record("123/198", "123/198", .42, t + 180);
        window.record("123/198", "Illustration Rare 123/198", .61, t + 360);

        assertEquals(2, window.votes("123/198", t + 360));
        String merged = window.mergedText("123/198", t + 360);
        assertTrue(merged.contains("Tapu Bulu"));
        assertTrue(merged.contains("123/198"));
    }

    @Test
    public void excludesConflictingCollectorEvidence() {
        EvidenceWindow window = new EvidenceWindow();
        long t = 2000;
        window.record("111/200", "Wrong Card 111/200", .60, t);
        window.record("222/200", "Right Card 222/200", .66, t + 150);
        window.record("222/200", "Right Card", .70, t + 300);

        String merged = window.mergedText("222/200", t + 300);
        assertTrue(merged.contains("Right Card"));
        assertFalse(merged.contains("Wrong Card"));
    }

    @Test
    public void oldVotesExpire() {
        EvidenceWindow window = new EvidenceWindow();
        window.record("050/100", "050/100", .5, 0);
        window.record("050/100", "050/100", .5, 200);
        assertEquals(2, window.votes("050/100", 500));
        assertEquals(0, window.votes("050/100", 3000));
    }
}
