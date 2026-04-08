package com.lm.inventoryvoice

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * This service runs in the background whenever accessibility is enabled.
 * It does two things:
 *   1. Tracks which text field is currently focused in InventoryBase
 *   2. Exposes injectText() so OverlayService can push transcribed speech into that field
 */
class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        // Singleton reference so OverlayService can call injectText()
        var instance: VoiceAccessibilityService? = null
            private set

        // The currently focused editable node in InventoryBase
        private var focusedNode: AccessibilityNodeInfo? = null

        /**
         * Called by OverlayService after transcription completes.
         * Inserts the transcribed text into whatever field is focused in InventoryBase.
         * Returns true if injection succeeded.
         */
        fun injectText(text: String): Boolean {
            val node = focusedNode ?: return false
            return try {
                val args = Bundle().apply {
                    putString(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Appends text to whatever is already in the focused field, with a space separator.
         * Useful for multiple dictation taps on the same field.
         */
        fun appendText(text: String): Boolean {
            val node = focusedNode ?: return false
            return try {
                val existing = node.text?.toString()?.trimEnd() ?: ""
                val combined = if (existing.isEmpty()) text else "$existing $text"
                val args = Bundle().apply {
                    putString(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        combined
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // We only care about focus events on editable (text input) fields
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val node = event.source ?: return
            if (node.isEditable) {
                // Refresh the reference — stale nodes can cause silent failures
                focusedNode = AccessibilityNodeInfo.obtain(node)
            }
        }

        // Also clear focus when the window changes away from InventoryBase
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg != "com.radweb.ib" && pkg != packageName) {
                focusedNode = null
            }
        }
    }

    override fun onInterrupt() {
        focusedNode = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        focusedNode = null
    }
}
