package ro.bara.whatsappcontactphotosync

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.min

interface SyncListener {
    fun onProgress(processed: Int, total: Int, updated: Int, skipped: Int)
    fun onFinished(updated: Int, skipped: Int, total: Int)
    fun onLog(message: String)
    fun onCalibrationCaptured()
}

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: WhatsAppAccessibilityService? = null
        // Held in memory instead of round-tripping through a file — avoids
        // any disk I/O/path/decode failure between capture and the
        // calibration screen.
        @Volatile var lastCalibrationBitmap: Bitmap? = null
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val CONTACT_NAME_VIEW_ID = "com.whatsapp:id/conversation_contact_name"
        private const val TAG = "WaSync"
    }

    var listener: SyncListener? = null

    private val main = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var contacts: List<PhoneContact> = emptyList()
    private var index = 0
    private var running = false
    private var waitingForChat = false
    private var waitingForContactInfo = false
    private var current: PhoneContact? = null
    private var updated = 0
    private var skipped = 0

    private fun log(message: String) {
        Log.d(TAG, message)
        listener?.onLog(message)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running || event == null) return
        if (event.packageName?.toString() != WHATSAPP_PACKAGE) return

        val root = rootInActiveWindow ?: return

        if (waitingForChat && current != null) {
            // We are on the chat screen. Click the header/name to enter contact info.
            val node = findChatHeaderNode(root, current!!.name)
            if (node != null) {
                val parent = clickableAncestor(node)
                if (parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    log("[${current?.name}] chat header found, opening contact info")
                    waitingForChat = false
                    waitingForContactInfo = true
                    // WhatsApp blocks screenshots of the full-size profile
                    // photo viewer (blacks them out, since ~2024, by design
                    // — not something we can work around). The contact-info
                    // screen's small avatar thumbnail is NOT protected, so
                    // we screenshot that directly instead of tapping into
                    // the full photo.
                    main.postDelayed({ takeContactInfoScreenshot() }, 900)
                }
            }
        }
    }

    fun startManualSync(limit: Int? = null) {
        if (running) return

        val repo = ContactRepository(this)
        val all = repo.loadContacts()
        contacts = if (limit != null && limit > 0) all.take(limit) else all
        index = 0
        updated = 0
        skipped = 0
        running = contacts.isNotEmpty()

        if (!running) {
            listener?.onFinished(0, 0, 0)
            return
        }
        processNext()
    }

    fun startCalibrationCapture() {
        log("Calibrare: ai 10 secunde — deschide WhatsApp și intră pe ecranul de informații al unui contact cu poză (nu apăsa pe avatar, doar deschide chat-ul și intră pe numele contactului)")
        main.postDelayed({ captureForCalibration() }, 10000)
    }

    private fun captureForCalibration() {
        if (Build.VERSION.SDK_INT < 30) {
            log("Calibrare: necesită Android 11+")
            return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        hardwareResultToBitmap(result) { bitmap ->
                            try {
                                if (bitmap != null) {
                                    lastCalibrationBitmap?.recycle()
                                    lastCalibrationBitmap = bitmap
                                    log("Calibrare: captură reușită (${bitmap.width}x${bitmap.height})")
                                    listener?.onCalibrationCaptured()
                                } else {
                                    log("Calibrare: captura a eșuat (bitmap null)")
                                }
                            } catch (e: Exception) {
                                log("Calibrare: eroare — ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        log("Calibrare: eroare — ${e.message}")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    log("Calibrare: takeScreenshot a eșuat, cod=$errorCode")
                }
            }
        )
    }

    /**
     * Converts the screenshot's raw GPU-only HardwareBuffer into a normal,
     * CPU-readable Bitmap via compress()+decode, which the docs guarantee
     * works from API 30 onward.
     */
    private fun hardwareResultToBitmap(result: ScreenshotResult, onReady: (Bitmap?) -> Unit) {
        val hb = result.hardwareBuffer
        val cs = result.colorSpace
        val hwBitmap = Bitmap.wrapHardwareBuffer(hb, cs)
        hb.close()
        if (hwBitmap == null) {
            onReady(null)
            return
        }
        val buffer = ByteArrayOutputStream()
        val ok = hwBitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        hwBitmap.recycle()
        if (!ok) {
            onReady(null)
            return
        }
        val bytes = buffer.toByteArray()
        onReady(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    fun stopSync() {
        if (!running) return
        running = false
        waitingForChat = false
        waitingForContactInfo = false
        val processed = index
        current = null
        listener?.onFinished(updated, skipped, processed)
    }

    private fun processNext() {
        if (!running) return

        if (index >= contacts.size) {
            running = false
            current = null
            listener?.onFinished(updated, skipped, contacts.size)
            return
        }

        listener?.onProgress(index, contacts.size, updated, skipped)
        current = contacts[index++]
        val number = ContactRepository(this).normalize(current!!.phone)

        if (number.length < 8) {
            skipped++
            processNext()
            return
        }

        waitingForChat = true
        waitingForContactInfo = false

        log("[${current!!.name}] opening wa.me/$number")

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$number")
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            main.postDelayed({
                if (waitingForChat) {
                    log("[${current?.name}] timed out waiting for chat header (probably not on WhatsApp)")
                    waitingForChat = false
                    skipped++
                    // Clear any lingering WhatsApp dialog/screen (e.g. "this
                    // number isn't on WhatsApp") before moving on, so it can't
                    // be misread as part of the next contact's flow.
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    main.postDelayed({ processNext() }, 500)
                }
            }, 4500)
        } catch (e: Exception) {
            log("[${current?.name}] failed to open WhatsApp: ${e.message}")
            waitingForChat = false
            skipped++
            processNext()
        }
    }

    private fun takeContactInfoScreenshot() {
        if (!running || !waitingForContactInfo) return

        if (Build.VERSION.SDK_INT < 30) {
            finishCurrent()
            return
        }

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        hardwareResultToBitmap(result) { bitmap ->
                            try {
                                if (bitmap != null) {
                                    val photo = extractAvatar(bitmap)
                                    bitmap.recycle()

                                    if (photo != null && current != null) {
                                        try {
                                            ContactRepository(this@WhatsAppAccessibilityService)
                                                .setPhoto(current!!.contactId, photo)
                                            log("[${current?.name}] photo saved (${photo.size} bytes)")
                                            updated++
                                        } catch (e: Exception) {
                                            log("[${current?.name}] setPhoto failed: ${e.message}")
                                            skipped++
                                        }
                                    } else {
                                        log("[${current?.name}] crop produced no usable photo")
                                        skipped++
                                    }
                                } else {
                                    log("[${current?.name}] screenshot bitmap was null")
                                    skipped++
                                }
                            } catch (e: Exception) {
                                log("[${current?.name}] unexpected error handling screenshot: ${e.message}")
                                skipped++
                            }
                            finishCurrent()
                        }
                    } catch (e: Exception) {
                        // Never let an unexpected error here stall the whole
                        // batch — log it, skip this contact, keep going.
                        log("[${current?.name}] unexpected error handling screenshot: ${e.message}")
                        skipped++
                        finishCurrent()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    log("[${current?.name}] takeScreenshot failed, errorCode=$errorCode")
                    skipped++
                    finishCurrent()
                }
            }
        )
    }

    private fun extractAvatar(screen: Bitmap): ByteArray? {
        // WhatsApp's contact-info screen shows a small round avatar near the
        // top. If the user has calibrated a crop (via "Calibrează captura"
        // in the app), use it — an exact match for their device/WhatsApp
        // version. Otherwise fall back to a conservative guessed region.
        val w = screen.width
        val h = screen.height
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int

        val calibration = CalibrationStore.load(this)
        if (calibration != null) {
            left = (calibration.left * w).toInt()
            top = (calibration.top * h).toInt()
            right = (calibration.right * w).toInt()
            bottom = (calibration.bottom * h).toInt()
        } else {
            val side = (min(w, h) * 0.48f).toInt()
            left = (w - side) / 2
            top = (h * 0.08f).toInt()
            right = left + side
            bottom = top + side
        }

        if (left < 0 || top < 0 || right > w || bottom > h || right <= left || bottom <= top) return null

        val crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top)

        val out = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, 92, out)
        crop.recycle()

        val bytes = out.toByteArray()
        return if (bytes.size > 2000) bytes else null
    }

    private fun finishCurrent() {
        waitingForContactInfo = false

        main.postDelayed({
            if (running) performGlobalAction(GLOBAL_ACTION_BACK)
        }, 300)

        main.postDelayed({
            processNext()
        }, 800)
    }

    private fun findChatHeaderNode(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId(CONTACT_NAME_VIEW_ID).firstOrNull()?.let { return it }
        root.findNodeByText(name)?.let { return it }
        return root.findNodeByPartialText(name)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(5) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
        return null
    }

    override fun onInterrupt() {
        running = false
    }

    private fun AccessibilityNodeInfo.findNodeByText(value: String): AccessibilityNodeInfo? {
        val matches = findAccessibilityNodeInfosByText(value)
        return matches.firstOrNull()
    }

    private fun AccessibilityNodeInfo.findNodeByPartialText(value: String): AccessibilityNodeInfo? {
        // Match against whole name tokens only (e.g. "Ion" from "Ion Popescu"),
        // never arbitrary substrings — a loose "contains either way" match used
        // to let short, unrelated dialog texts (e.g. a stray "OK"/"Nu" button on
        // the "this number isn't on WhatsApp" screen) false-positive as the
        // contact header, which misfired clicks and corrupted later contacts.
        val tokens = value.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        if (tokens.isEmpty()) return null
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found != null) return
            val text = (n.text?.toString() ?: n.contentDescription?.toString())?.trim()?.lowercase()
            if (!text.isNullOrEmpty() && text.length >= 3 && tokens.any { it == text || text.contains(it) }) {
                found = n
                return
            }
            for (i in 0 until n.childCount) {
                walk(n.getChild(i))
                if (found != null) return
            }
        }
        walk(this)
        return found
    }
}
