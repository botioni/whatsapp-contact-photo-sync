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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.min

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: WhatsAppAccessibilityService? = null
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
    }

    private val main = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var contacts: List<PhoneContact> = emptyList()
    private var index = 0
    private var running = false
    private var waitingForChat = false
    private var waitingForProfile = false
    private var current: PhoneContact? = null
    private var updated = 0
    private var skipped = 0

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
        val text = root.textContent()

        if (waitingForChat && current != null) {
            // We are on the chat screen. Click the header/name to enter contact info.
            val name = current!!.name
            val node = root.findNodeByText(name)
            if (node != null) {
                val parent = clickableAncestor(node)
                if (parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    waitingForChat = false
                    waitingForProfile = true
                    main.postDelayed({ takeProfileScreenshot() }, 900)
                }
            }
        }
    }

    fun startManualSync() {
        if (running) return

        val repo = ContactRepository(this)
        contacts = repo.loadContacts()
        index = 0
        updated = 0
        skipped = 0
        running = contacts.isNotEmpty()

        if (!running) return
        processNext()
    }

    private fun processNext() {
        if (!running) return

        if (index >= contacts.size) {
            running = false
            current = null
            return
        }

        current = contacts[index++]
        val number = ContactRepository(this).normalize(current!!.phone)

        if (number.length < 8) {
            skipped++
            processNext()
            return
        }

        waitingForChat = true
        waitingForProfile = false

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$number")
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            main.postDelayed({
                if (waitingForChat) {
                    waitingForChat = false
                    skipped++
                    processNext()
                }
            }, 4500)
        } catch (_: Exception) {
            waitingForChat = false
            skipped++
            processNext()
        }
    }

    private fun takeProfileScreenshot() {
        if (!running || !waitingForProfile) return

        if (Build.VERSION.SDK_INT < 30) {
            finishCurrent()
            return
        }

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val cs = result.colorSpace
                    val bitmap = Bitmap.wrapHardwareBuffer(hb, cs)
                    hb.close()

                    if (bitmap != null) {
                        val photo = extractAvatar(bitmap)
                        bitmap.recycle()

                        if (photo != null && current != null) {
                            try {
                                ContactRepository(this@WhatsAppAccessibilityService)
                                    .setPhoto(current!!.contactId, photo)
                                updated++
                            } catch (_: Exception) {
                                skipped++
                            }
                        } else {
                            skipped++
                        }
                    } else {
                        skipped++
                    }
                    finishCurrent()
                }

                override fun onFailure(errorCode: Int) {
                    skipped++
                    finishCurrent()
                }
            }
        )
    }

    private fun extractAvatar(screen: Bitmap): ByteArray? {
        // WhatsApp's contact-info screen normally places the profile avatar
        // near the upper-center area. This is deliberately conservative.
        // The UI can change between WhatsApp versions, so this is the part
        // most likely to need adjustment.
        val w = screen.width
        val h = screen.height
        val side = min(w, h) * 0.48f
        val left = ((w - side) / 2f).toInt()
        val top = (h * 0.08f).toInt()
        val bottom = min(h, top + side.toInt())

        if (left < 0 || top < 0 || left + side > w || bottom <= top) return null

        val crop = Bitmap.createBitmap(
            screen, left, top,
            min(side.toInt(), w - left),
            bottom - top
        )

        val out = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, 92, out)
        crop.recycle()

        val bytes = out.toByteArray()
        return if (bytes.size > 2000) bytes else null
    }

    private fun finishCurrent() {
        waitingForProfile = false
        main.postDelayed({
            // Return to the app/browser state and continue the manually started batch.
            processNext()
        }, 700)
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

    private fun AccessibilityNodeInfo.textContent(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append('\n') }
            n.contentDescription?.let { sb.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(this)
        return sb.toString()
    }

    private fun AccessibilityNodeInfo.findNodeByText(value: String): AccessibilityNodeInfo? {
        val matches = findAccessibilityNodeInfosByText(value)
        return matches.firstOrNull()
    }
}
