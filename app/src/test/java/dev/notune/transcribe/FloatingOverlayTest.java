package dev.notune.transcribe;

import org.junit.Test;
import static org.junit.Assert.*;

public class FloatingOverlayTest {

    @Test
    public void testPasteTextNullHandling() {
        assertFalse(FloatingDictationAccessibilityService.pasteText(null, "hello"));
        assertFalse(FloatingDictationAccessibilityService.pasteText(null, null));
    }

    @Test
    public void testClipboardFallbackNullHandling() {
        assertFalse(FloatingDictationAccessibilityService.copyToClipboardFallback(null, "test"));
        assertFalse(FloatingDictationAccessibilityService.copyToClipboardFallback(null, ""));
    }

    @Test
    public void testAccessibilityServiceInstanceLifecycle() {
        assertNull(FloatingDictationAccessibilityService.getInstance());
        assertFalse(FloatingDictationAccessibilityService.isEnabled());
    }
}
