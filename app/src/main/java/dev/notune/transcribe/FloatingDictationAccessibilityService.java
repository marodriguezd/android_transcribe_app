package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

/**
 * Accessibility Service to track focused input fields across applications
 * and perform direct text insertion (ACTION_PASTE / ACTION_SET_TEXT) or fallback
 * clipboard copy.
 */
public class FloatingDictationAccessibilityService extends AccessibilityService {

    private static final String TAG = "FloatingAccessibility";
    private static volatile FloatingDictationAccessibilityService sInstance = null;

    private AccessibilityNodeInfo mLastFocusedNode = null;

    public static FloatingDictationAccessibilityService getInstance() {
        return sInstance;
    }

    public static boolean isEnabled() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "FloatingDictationAccessibilityService connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (sInstance == this) {
            sInstance = null;
        }
        clearLastFocusedNode();
        Log.i(TAG, "FloatingDictationAccessibilityService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        clearLastFocusedNode();
        super.onDestroy();
        Log.i(TAG, "FloatingDictationAccessibilityService destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The event stream comes from every window on screen and can race with
        // windows closing. A single uncaught exception here crashes the whole
        // accessibility service, which the system then shows as "keeps stopping"
        // and repeatedly restarts — perceived as constant app crashes. Never let
        // an event take the service down.
        try {
            if (event == null) {
                return;
            }
            int eventType = event.getEventType();

            // The focused window changed: the previously remembered node belongs
            // to a window that may already be gone. Recycle it so a later
            // performInsert() cannot target a stale/destroyed node.
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clearLastFocusedNode();
                return;
            }

            if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
                    || eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                    || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    if (source.isEditable() && source.isEnabled()) {
                        clearLastFocusedNode();
                        mLastFocusedNode = source;
                    } else {
                        source.recycle();
                    }
                }
            }
        } catch (Throwable t) {
            // Never crash the accessibility service from a single bad event.
            Log.w(TAG, "Error handling accessibility event", t);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "FloatingDictationAccessibilityService interrupted");
    }

    private synchronized void clearLastFocusedNode() {
        if (mLastFocusedNode != null) {
            try {
                mLastFocusedNode.recycle();
            } catch (Exception e) {
                // Ignore if already recycled
            }
            mLastFocusedNode = null;
        }
    }

    /**
     * Attempts to paste text into the active focused text field using the accessibility service.
     * If the service is not running or insertion fails, falls back to clipboard copy.
     *
     * @param context Application context
     * @param text    Text to paste
     * @return true if text was inserted or copied to clipboard successfully
     */
    public static boolean pasteText(Context context, CharSequence text) {
        FloatingDictationAccessibilityService service = sInstance;
        if (service != null && service.performInsert(text)) {
            return true;
        }
        return copyToClipboardFallback(context, text);
    }

    /**
     * Tiered text insertion into the currently focused window node:
     * Priority 1: ACTION_PASTE via ClipboardManager
     * Priority 2: ACTION_SET_TEXT via ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
     * Priority 3: Fallback clipboard write + Toast notification
     *
     * @param text Text to insert
     * @return true if ACTION_PASTE or ACTION_SET_TEXT succeeded
     */
    public boolean performInsert(CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }

        AccessibilityNodeInfo rootNode = null;
        AccessibilityNodeInfo targetNode = null;

        try {
            rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                targetNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (targetNode == null) {
                    targetNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding focus node in active window", e);
        } finally {
            if (rootNode != null) {
                try {
                    rootNode.recycle();
                } catch (Exception ignored) {
                }
            }
        }

        if (targetNode == null) {
            synchronized (this) {
                if (mLastFocusedNode != null) {
                    try {
                        targetNode = AccessibilityNodeInfo.obtain(mLastFocusedNode);
                    } catch (Exception e) {
                        targetNode = null;
                    }
                }
            }
        }

        if (targetNode != null) {
            try {
                if (targetNode.isEditable() && targetNode.isEnabled()) {
                    // Priority 1: ACTION_PASTE via ClipboardManager
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        ClipData clip = ClipData.newPlainText("transcribed_text", text);
                        cm.setPrimaryClip(clip);
                    }
                    if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                        Log.i(TAG, "Successfully pasted text via ACTION_PASTE");
                        return true;
                    }

                    // Priority 2: ACTION_SET_TEXT
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        Log.i(TAG, "Successfully set text via ACTION_SET_TEXT");
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error performing accessibility action", e);
            } finally {
                try {
                    targetNode.recycle();
                } catch (Exception ignored) {
                }
            }
        }

        // Priority 3: Fallback clipboard write
        return copyToClipboardFallback(this, text);
    }

    /**
     * Fallback clipboard copy when direct accessibility insertion is unavailable.
     *
     * @param context Application or service context
     * @param text    Text to copy
     * @return true if copied to clipboard
     */
    public static boolean copyToClipboardFallback(Context context, CharSequence text) {
        if (context == null || text == null || text.length() == 0) {
            return false;
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("transcribed_text", text);
                cm.setPrimaryClip(clip);
                Context appContext = context.getApplicationContext();
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(appContext, "Text copied to clipboard", Toast.LENGTH_SHORT).show();
                });
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying text to clipboard fallback", e);
        }
        return false;
    }
}
