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

    @Test
    public void testCalculateNearestEdgeXLeft() {
        int screenWidth = 1080;
        int bubbleWidth = 120;
        // Center X = 100 + 60 = 160 < 540 -> snaps to Left edge (0)
        assertEquals(0, FloatingOverlayService.calculateNearestEdgeX(100, bubbleWidth, screenWidth));
    }

    @Test
    public void testCalculateNearestEdgeXRight() {
        int screenWidth = 1080;
        int bubbleWidth = 120;
        // Center X = 800 + 60 = 860 > 540 -> snaps to Right edge (1080 - 120 = 960)
        assertEquals(960, FloatingOverlayService.calculateNearestEdgeX(800, bubbleWidth, screenWidth));
    }

    @Test
    public void testCalculateNearestEdgeXMidpoint() {
        int screenWidth = 1000;
        int bubbleWidth = 100;
        // Center X = 450 + 50 = 500 == 500 -> Right edge (900)
        assertEquals(900, FloatingOverlayService.calculateNearestEdgeX(450, bubbleWidth, screenWidth));
        // Center X = 449 + 50 = 499 < 500 -> Left edge (0)
        assertEquals(0, FloatingOverlayService.calculateNearestEdgeX(449, bubbleWidth, screenWidth));
    }

    @Test
    public void testClampYWithinBounds() {
        int screenHeight = 1920;
        int bubbleHeight = 120;
        int statusBarHeight = 48;
        // Valid Y within range [48, 1800]
        assertEquals(500, FloatingOverlayService.clampY(500, bubbleHeight, screenHeight, statusBarHeight));
        // Y above status bar -> clamped to status bar
        assertEquals(48, FloatingOverlayService.clampY(10, bubbleHeight, screenHeight, statusBarHeight));
        // Y below screen height -> clamped to (1920 - 120) = 1800
        assertEquals(1800, FloatingOverlayService.clampY(2000, bubbleHeight, screenHeight, statusBarHeight));
    }

    @Test
    public void testCalculateDockedX() {
        int screenWidth = 1080;
        int bubbleWidth = 100;
        float peekRatio = 0.45f;
        int expectedPeekOffset = (int) (100 * 0.45f); // 45

        // Left edge docked -> -45
        assertEquals(-expectedPeekOffset, FloatingOverlayService.calculateDockedX(true, bubbleWidth, screenWidth, peekRatio));

        // Right edge docked -> 1080 - 100 + 45 = 1025
        int expectedRightDocked = 1080 - 100 + expectedPeekOffset;
        assertEquals(expectedRightDocked, FloatingOverlayService.calculateDockedX(false, bubbleWidth, screenWidth, peekRatio));
    }

    @Test
    public void testCalculateNearestEdgeXNegativeAndOverflow() {
        int screenWidth = 1080;
        int bubbleWidth = 120;
        // Negative position (e.g. pulled off left) -> snaps to 0
        assertEquals(0, FloatingOverlayService.calculateNearestEdgeX(-200, bubbleWidth, screenWidth));
        // Overflow position beyond screen -> snaps to right edge (960)
        assertEquals(960, FloatingOverlayService.calculateNearestEdgeX(1500, bubbleWidth, screenWidth));
    }

    @Test
    public void testCalculateNearestEdgeXNarrowScreen() {
        // Screen narrower than bubble width
        int screenWidth = 100;
        int bubbleWidth = 150;
        assertEquals(0, FloatingOverlayService.calculateNearestEdgeX(0, bubbleWidth, screenWidth));
    }

    @Test
    public void testClampYZeroStatusBarAndSmallScreen() {
        int screenHeight = 800;
        int bubbleHeight = 100;
        int statusBarHeight = 0;
        assertEquals(0, FloatingOverlayService.clampY(-50, bubbleHeight, screenHeight, statusBarHeight));
        assertEquals(700, FloatingOverlayService.clampY(900, bubbleHeight, screenHeight, statusBarHeight));
    }

    @Test
    public void testCalculateDockedXVariousPeekRatios() {
        int screenWidth = 1000;
        int bubbleWidth = 100;
        // 0% peek -> 0 on left, 900 on right
        assertEquals(0, FloatingOverlayService.calculateDockedX(true, bubbleWidth, screenWidth, 0.0f));
        assertEquals(900, FloatingOverlayService.calculateDockedX(false, bubbleWidth, screenWidth, 0.0f));
        // 50% peek -> -50 on left, 950 on right
        assertEquals(-50, FloatingOverlayService.calculateDockedX(true, bubbleWidth, screenWidth, 0.5f));
        assertEquals(950, FloatingOverlayService.calculateDockedX(false, bubbleWidth, screenWidth, 0.5f));
    }

    @Test
    public void testClampYStatusBarLargerThanMaxY() {
        int screenHeight = 150;
        int bubbleHeight = 120;
        int statusBarHeight = 100;
        int clampedY = FloatingOverlayService.clampY(50, bubbleHeight, screenHeight, statusBarHeight);
        assertEquals(30, clampedY);
        assertTrue(clampedY + bubbleHeight <= screenHeight);
    }

    @Test
    public void testOrientationChangeScalingPreservesRightEdge() {
        int oldScreenWidth = 1080;
        int newScreenWidth = 2400;
        int bubbleWidth = 120;
        int rightEdgeXPortrait = 960;

        int scaledX = (int) ((long) rightEdgeXPortrait * newScreenWidth / oldScreenWidth);
        int snappedX = FloatingOverlayService.calculateNearestEdgeX(scaledX, bubbleWidth, newScreenWidth);
        assertEquals(2280, snappedX);
    }
}
