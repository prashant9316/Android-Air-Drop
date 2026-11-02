package com.caremate.assistant.device

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.caremate.assistant.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The "hands and eyes" of the assistant. Once enabled in Settings > Accessibility it can
 * read what is on screen in any app, tap, type, scroll, press Back/Home, and take
 * screenshots, which is what lets Claude operate apps like WhatsApp for the user.
 * It also adds an on-screen accessibility button that opens CareMate listening.
 */
class ScreenAgentService : AccessibilityService() {

    private val nodeIndex = HashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        startActivity(
                            Intent(this@ScreenAgentService, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_LISTEN, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    }
                }
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ reading

    /** Compact, numbered description of everything visible, for the model to act on. */
    fun describeScreen(maxLines: Int = 180): String {
        nodeIndex.clear()
        val sb = StringBuilder()
        val roots = windows.mapNotNull { w -> w.root?.let { w to it } }
            .sortedByDescending { it.first.layer }
        if (roots.isEmpty()) rootInActiveWindow?.let { walk(it, 0, sb, maxLines) }
        for ((w, root) in roots) {
            if (sb.lines().size > maxLines) break
            val title = w.title?.toString() ?: root.packageName?.toString() ?: "window"
            sb.append("## ").append(title).append('\n')
            walk(root, 0, sb, maxLines)
        }
        return sb.toString().ifBlank { "Screen is empty or not readable." }
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, maxLines: Int) {
        if (sb.count { it == '\n' } > maxLines || !node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        if (interesting) {
            val id = nodeIndex.size + 1
            nodeIndex[id] = node
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
            sb.append("  ".repeat(depth.coerceAtMost(6))).append('[').append(id).append("] ").append(cls)
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append('"')
            if (desc.isNotEmpty() && desc != text) sb.append(" desc=\"").append(desc.take(80)).append('"')
            if (hint.isNotEmpty() && text.isEmpty()) sb.append(" hint=\"").append(hint.take(60)).append('"')
            node.viewIdResourceName?.substringAfter(":id/")?.let { sb.append(" id=").append(it) }
            val flags = buildList {
                if (node.isClickable) add("tap"); if (node.isLongClickable) add("longtap")
                if (node.isEditable) add("edit"); if (node.isScrollable) add("scroll")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (node.isFocused) add("focused"); if (!node.isEnabled) add("disabled")
            }
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append('\n')
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + if (interesting) 1 else 0, sb, maxLines) }
    }

    /** JPEG screenshot as base64 (Android 11+). */
    suspend fun screenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    if (hw == null) { cont.resume(null); return }
                    val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                    val scale = 1280f / maxOf(soft.width, soft.height)
                    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true) else soft
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    cont.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                }
                override fun onFailure(errorCode: Int) = cont.resume(null)
            })
        }
    }

    // ------------------------------------------------------------------ acting

    private fun findNode(target: String): AccessibilityNodeInfo? {
        target.trim().removePrefix("[").removeSuffix("]").toIntOrNull()?.let { id -> nodeIndex[id]?.let { return it } }
        if (nodeIndex.isEmpty()) describeScreen()
        val t = target.trim().lowercase()
        val all = nodeIndex.values.toList()
        return all.firstOrNull { it.text?.toString()?.trim()?.lowercase() == t || it.contentDescription?.toString()?.trim()?.lowercase() == t }
            ?: all.firstOrNull { it.viewIdResourceName?.substringAfter(":id/")?.lowercase() == t }
            ?: all.firstOrNull { (it.text?.toString()?.lowercase()?.contains(t) == true) || (it.contentDescription?.toString()?.lowercase()?.contains(t) == true) }
    }

    suspend fun tap(target: String, long: Boolean = false): String {
        val node = findNode(target) ?: return "Could not find \"$target\" on screen. Call read_screen to refresh."
        var n: AccessibilityNodeInfo? = node
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (n != null && !(if (long) n.isLongClickable else n.isClickable)) n = n.parent
        val ok = n?.performAction(action) == true || gestureTap(node, long)
        nodeIndex.clear()
        delay(700)
        return if (ok) "Tapped \"$target\"." else "Tap on \"$target\" failed."
    }

    private suspend fun gestureTap(node: AccessibilityNodeInfo, long: Boolean): Boolean {
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tapAt(r.exactCenterX(), r.exactCenterY(), long)
    }

    suspend fun tapAt(x: Float, y: Float, long: Boolean = false): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60)).build()
        return dispatch(g)
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    suspend fun typeText(text: String, target: String?, submit: Boolean): String {
        val node = (target?.let { findNode(it) })
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: nodeIndex.values.firstOrNull { it.isEditable }
            ?: run { describeScreen(); nodeIndex.values.firstOrNull { it.isEditable } }
            ?: return "No text box found on screen."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (submit && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        nodeIndex.clear()
        delay(400)
        return if (ok) "Typed text." else "Could not type into that field."
    }

    suspend fun scroll(direction: String): String {
        if (nodeIndex.isEmpty()) describeScreen()
        val forward = direction.lowercase() in setOf("down", "forward", "right", "next")
        val scrollable = nodeIndex.values.filter { it.isScrollable }.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height() }
        val ok = scrollable?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) == true || swipe(forward, direction.lowercase() in setOf("left", "right"))
        nodeIndex.clear()
        delay(600)
        return if (ok) "Scrolled $direction." else "Could not scroll."
    }

    private suspend fun swipe(forward: Boolean, horizontal: Boolean): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val path = Path()
        if (horizontal) {
            if (forward) { path.moveTo(w * 0.85f, h * 0.5f); path.lineTo(w * 0.15f, h * 0.5f) }
            else { path.moveTo(w * 0.15f, h * 0.5f); path.lineTo(w * 0.85f, h * 0.5f) }
        } else {
            if (forward) { path.moveTo(w * 0.5f, h * 0.75f); path.lineTo(w * 0.5f, h * 0.3f) }
            else { path.moveTo(w * 0.5f, h * 0.3f); path.lineTo(w * 0.5f, h * 0.75f) }
        }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build())
    }

    fun press(button: String): String {
        val action = when (button.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "Not supported on this phone."
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return "Not supported on this phone."
            else -> return "Unknown button $button"
        }
        nodeIndex.clear()
        return if (performGlobalAction(action)) "Pressed $button." else "Could not press $button."
    }

    /** Clicks WhatsApp's send button after a prefilled chat opens. */
    suspend fun clickWhatsAppSend(): Boolean {
        repeat(12) {
            delay(500)
            val root = rootInActiveWindow ?: return@repeat
            val btn = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send").firstOrNull()
                ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send").firstOrNull()
                ?: root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable }
            if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    companion object {
        @Volatile var instance: ScreenAgentService? = null
            private set
        @Volatile var currentPackage: String = ""
    }
}
