package ro.bara.whatsappcontactphotosync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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
        // calibration screen, which previously showed up as a blank/black
        // image with no clear cause.
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
    private var waitingForAvatar = false
    private var waitingForPhotoConfirm = false
    private var waitingForFullPhoto = false
    private var avatarOpened = false
    private var current: PhoneContact? = null
    private var updated = 0
    private var skipped = 0
    private var avatarAttempts = 0
    private var avatarConfirmRequestId = 0
    private var avatarClickedAt = 0L

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
                    waitingForAvatar = true
                    avatarAttempts = 0
                    main.postDelayed({ tryClickAvatar() }, 900)
                }
            }
            return
        }

        if (waitingForPhotoConfirm) {
            // WhatsApp's full-photo viewer isn't necessarily a new window —
            // on some versions it's a same-window transition, which only
            // fires TYPE_WINDOW_CONTENT_CHANGED, not TYPE_WINDOW_STATE_CHANGED.
            // Waiting only for the latter meant we never confirmed the photo
            // opened and always fell through to the timeout, even when the
            // tap worked and a photo existed.
            val sinceClick = android.os.SystemClock.elapsedRealtime() - avatarClickedAt
            val isRelevantEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            if (isRelevantEvent && sinceClick > 300) {
                confirmPhotoOpened()
            }
        }
    }

    private fun confirmPhotoOpened() {
        if (!waitingForPhotoConfirm) return
        waitingForPhotoConfirm = false
        avatarOpened = true
        waitingForFullPhoto = true
        log("[${current?.name}] full-size photo screen opened")
        // Give the open transition/animation time to finish rendering the
        // actual photo before screenshotting, otherwise we can capture a
        // black/transitional frame.
        main.postDelayed({ takeProfileScreenshot() }, 1100)
    }

    private fun avatarConfirmTimedOut(requestId: Int) {
        if (!waitingForPhotoConfirm || requestId != avatarConfirmRequestId) return
        waitingForPhotoConfirm = false
        log("[${current?.name}] tapping avatar had no effect — likely no profile photo set, skipping")
        skipped++
        avatarOpened = false
        finishCurrent()
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
        log("Calibrare: ai 10 secunde — deschide WhatsApp, intră pe contact, apasă pe avatar și așteaptă nemișcat pe poza mare")
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
                        val bitmap = hardwareResultToBitmap(result)
                        if (bitmap != null) {
                            lastCalibrationBitmap?.recycle()
                            lastCalibrationBitmap = bitmap
                            log("Calibrare: captură reușită (${bitmap.width}x${bitmap.height})")
                            // Brightness is just a diagnostic hint — never let
                            // it block showing the calibration screen if it
                            // fails for any reason.
                            try {
                                val avgBrightness = averageBrightness(bitmap)
                                log("Calibrare: luminozitate medie: $avgBrightness/255")
                                if (avgBrightness < 20) {
                                    log("Calibrare: ecranul pare aproape complet negru — probabil nu erai încă pe poza mare când s-a făcut captura. Verifică imaginea și, dacă nu se vede poza, apasă din nou Calibrează și fii mai rapid la navigare.")
                                }
                            } catch (e: Exception) {
                                log("Calibrare: nu am putut calcula luminozitatea — ${e.message}")
                            }
                            listener?.onCalibrationCaptured()
                        } else {
                            log("Calibrare: captura a eșuat (bitmap null)")
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

    private fun hardwareResultToBitmap(result: ScreenshotResult): Bitmap? {
        val hb = result.hardwareBuffer
        val cs = result.colorSpace
        val hwBitmap = Bitmap.wrapHardwareBuffer(hb, cs)
        hb.close()
        if (hwBitmap == null) return null

        // Both hwBitmap.copy(ARGB_8888, ...) and drawing it through a Canvas
        // came back solid black on this device — confirmed by testing, not
        // just theory. Bitmap.createBitmap(source, x, y, w, h) is what the
        // very first version of this app used (no manual conversion at all)
        // and it produced a real, usable photo, so use that same path here.
        //
        // Important: requesting the *exact* same width/height as the source
        // makes Android just hand back the original HARDWARE bitmap
        // unchanged instead of creating a real copy — the original code
        // never hit this because it always cropped a smaller sub-region.
        // Trim 2px so a genuine conversion always happens.
        val w = (hwBitmap.width - 2).coerceAtLeast(1)
        val h = (hwBitmap.height - 2).coerceAtLeast(1)
        val out = Bitmap.createBitmap(hwBitmap, 0, 0, w, h)
        hwBitmap.recycle()
        return out
    }

    private fun averageBrightness(bitmap: Bitmap): Int {
        val cols = 10
        val rows = 10
        var total = 0L
        var samples = 0
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val x = (bitmap.width * (i + 0.5f) / cols).toInt().coerceIn(0, bitmap.width - 1)
                val y = (bitmap.height * (j + 0.5f) / rows).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                total += (r + g + b) / 3
                samples++
            }
        }
        return (total / samples).toInt()
    }

    fun stopSync() {
        if (!running) return
        running = false
        waitingForChat = false
        waitingForAvatar = false
        waitingForPhotoConfirm = false
        waitingForFullPhoto = false
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
        waitingForAvatar = false
        waitingForPhotoConfirm = false
        waitingForFullPhoto = false
        avatarOpened = false

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

    private fun tryClickAvatar() {
        if (!running || !waitingForAvatar || current == null) return
        avatarAttempts++

        val root = rootInActiveWindow
        val avatar = root?.let { findAvatarNode(it) }

        if (avatar != null) {
            val clickTarget = if (avatar.isClickable) avatar else clickableAncestor(avatar) ?: avatar
            if (clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("[${current?.name}] avatar clicked (attempt $avatarAttempts), waiting to see if a photo opens")
                waitingForAvatar = false
                waitingForPhotoConfirm = true
                avatarClickedAt = android.os.SystemClock.elapsedRealtime()
                avatarConfirmRequestId++
                val requestId = avatarConfirmRequestId
                main.postDelayed({ avatarConfirmTimedOut(requestId) }, 2000)
                return
            }
        }

        if (avatarAttempts >= 6) {
            // Couldn't find/click the avatar to open the full-size photo.
            // Fall back to screenshotting the contact-info screen as-is.
            log("[${current?.name}] avatar not found after $avatarAttempts attempts, using fallback crop")
            waitingForAvatar = false
            waitingForFullPhoto = true
            avatarOpened = false
            takeProfileScreenshot()
            return
        }

        main.postDelayed({ tryClickAvatar() }, 500)
    }

    private val avatarViewIds = listOf(
        "com.whatsapp:id/photo_btn",
        "com.whatsapp:id/photo",
        "com.whatsapp:id/contact_photo",
        "com.whatsapp:id/img"
    )

    private fun findAvatarNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in avatarViewIds) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }

        // Fallback: the avatar is a roughly square image, larger than toolbar
        // icons (back arrow, menu, etc.) but smaller than a full-screen photo.
        // Pick the biggest square-ish ImageView on screen instead of the
        // topmost one, since toolbar icons sit above the avatar and are small.
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val bounds = Rect()

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.className == "android.widget.ImageView") {
                n.getBoundsInScreen(bounds)
                val w = bounds.width()
                val h = bounds.height()
                val ratio = if (h == 0) 0f else w.toFloat() / h
                val squareish = ratio in 0.75f..1.35f
                if (squareish && w in 120..900 && h in 120..900) {
                    val area = w * h
                    if (area > bestArea) {
                        bestArea = area
                        best = n
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun takeProfileScreenshot() {
        if (!running || !waitingForFullPhoto) return

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
                        val bitmap = hardwareResultToBitmap(result)

                        if (bitmap != null) {
                            val photo = extractAvatar(bitmap)
                            bitmap.recycle()

                            if (photo != null && current != null) {
                                try {
                                    ContactRepository(this@WhatsAppAccessibilityService)
                                        .setPhoto(current!!.contactId, photo)
                                    log("[${current?.name}] photo saved (${photo.size} bytes, avatarOpened=$avatarOpened)")
                                    updated++
                                } catch (e: Exception) {
                                    log("[${current?.name}] setPhoto failed: ${e.message}")
                                    skipped++
                                }
                            } else {
                                log("[${current?.name}] crop produced no usable photo (avatarOpened=$avatarOpened)")
                                skipped++
                            }
                        } else {
                            log("[${current?.name}] screenshot bitmap was null")
                            skipped++
                        }
                    } catch (e: Exception) {
                        // Never let an unexpected error here stall the whole
                        // batch — log it, skip this contact, keep going.
                        log("[${current?.name}] unexpected error handling screenshot: ${e.message}")
                        skipped++
                    }
                    finishCurrent()
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
        // We are (normally) on WhatsApp's full-screen photo viewer, opened by
        // tapping the avatar from the contact-info screen. If the user has
        // calibrated a crop (via "Calibrează captura" in the app), use it —
        // it's an exact match for their device/WhatsApp version instead of a
        // guessed percentage. Otherwise fall back to a wide centered square.
        // If the avatar tap failed, this instead screenshots the contact-info
        // screen with a smaller, upper-centered crop as a fallback.
        val w = screen.width
        val h = screen.height
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int

        if (avatarOpened) {
            val calibration = CalibrationStore.load(this)
            if (calibration != null) {
                left = (calibration.left * w).toInt()
                top = (calibration.top * h).toInt()
                right = (calibration.right * w).toInt()
                bottom = (calibration.bottom * h).toInt()
            } else {
                val side = (min(w, h) * 0.92f).toInt()
                left = (w - side) / 2
                top = ((h - side) / 2).coerceAtLeast(0)
                right = left + side
                bottom = top + side
            }
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
        val backPresses = if (avatarOpened) 2 else 1
        waitingForFullPhoto = false
        avatarOpened = false

        repeat(backPresses) { i ->
            main.postDelayed({
                if (running) performGlobalAction(GLOBAL_ACTION_BACK)
            }, 300L * (i + 1))
        }

        main.postDelayed({
            processNext()
        }, 300L * backPresses + 500)
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
