package ro.bara.whatsappcontactphotosync

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyCharacterMap
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ro.bara.whatsappcontactphotosync.databinding.ActivityWebSyncBinding

/**
 * Runs WhatsApp Web inside an in-app WebView (desktop user-agent, so
 * WhatsApp serves the full desktop UI) and drives the same extraction
 * logic that was proven working via a real desktop browser — but here
 * the extracted photo is written straight into the contact via
 * ContactRepository, no file export/import round trip needed.
 *
 * WhatsApp Web still requires linking this WebView as its own device the
 * first time (Settings > Linked devices > Link with phone number on the
 * real WhatsApp app — no camera needed, just typing the code shown here).
 * That's a one-time manual step; everything after is automated.
 */
class WebSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebSyncBinding
    private lateinit var repo: ContactRepository
    private val main = Handler(Looper.getMainLooper())
    private val logLines = StringBuilder()
    private var foundCount = 0
    private var noPhotoCount = 0
    private var deletedCount = 0
    private var notOnWhatsappCount = 0
    private var errorCount = 0

    private var missingQueue: List<String> = emptyList()
    private var missingIndex = 0
    private var missingSearchActive = false

    private var loggedIn = false
    private var debugMode = false
    @Volatile private var deleteMissingEnabled = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* if denied, the sync still runs, it just won't show a notification */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ContactRepository(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        SyncForegroundService.stopCallback = {
            runOnUiThread {
                binding.webView.evaluateJavascript("window.__waStop = true;", null)
                missingSearchActive = false
                main.removeCallbacksAndMessages(null)
                updateProgress("Oprit din notificare.", missingIndex, missingQueue.size)
                if (missingIndex < missingQueue.size) {
                    binding.runMissingButton.text = "Continuă căutarea"
                }
                setRunningUI(false)
            }
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_USER_AGENT
        }
        binding.webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
        binding.webView.webViewClient = WebViewClient()
        binding.webView.loadUrl("https://web.whatsapp.com")

        main.postDelayed({ pollLoginState() }, 2000)

        binding.runMissingButton.setOnClickListener {
            startMissingSearch()
        }

        binding.stopButton.setOnClickListener {
            binding.webView.evaluateJavascript("window.__waStop = true;", null)
            missingSearchActive = false
            main.removeCallbacksAndMessages(null)
            updateProgress("Oprit.", missingIndex, missingQueue.size)
            SyncForegroundService.stop(this)
            if (missingIndex < missingQueue.size) {
                binding.runMissingButton.text = "Continuă căutarea"
            }
            setRunningUI(false)
        }

        binding.debugToggleButton.setOnClickListener {
            debugMode = !debugMode
            binding.logScroll.visibility = if (debugMode) View.VISIBLE else View.GONE
            updateWebViewVisibility()
        }

        binding.logoutButton.setOnClickListener {
            logout()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncForegroundService.stopCallback = null
    }

    /**
     * Polls until WhatsApp Web shows the chat list (i.e. this device is
     * linked), then reveals the controls and hides the WebView itself —
     * the browser stays alive and keeps running underneath (so its
     * measurements and clicks keep working), it's just not drawn, so the
     * user only sees our own progress UI, never WhatsApp's screen flicking
     * through contacts.
     */
    private fun pollLoginState() {
        if (loggedIn) return
        binding.webView.evaluateJavascript(LOGIN_CHECK_SCRIPT) { result ->
            if (result?.trim('"') == "true") {
                loggedIn = true
                binding.instructionCard.visibility = View.GONE
                binding.controlsCard.visibility = View.VISIBLE
                updateWebViewVisibility()
            } else {
                main.postDelayed({ pollLoginState() }, 2000)
            }
        }
    }

    /** The WebView is only ever visible before login (to show the linking code) or while Debug is on. */
    private fun updateWebViewVisibility() {
        binding.webView.visibility = if (!loggedIn || debugMode) View.VISIBLE else View.INVISIBLE
    }

    /** Idle: filter switch + start button. Running: just the heartbeat, progress and stop. */
    private fun setRunningUI(active: Boolean) {
        binding.setupGroup.visibility = if (active) View.GONE else View.VISIBLE
        binding.runningGroup.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun logout() {
        missingSearchActive = false
        missingQueue = emptyList()
        missingIndex = 0
        binding.runMissingButton.text = "Caută contactele"
        setRunningUI(false)
        main.removeCallbacksAndMessages(null)
        SyncForegroundService.stop(this)
        binding.webView.evaluateJavascript("window.__waStop = true;", null)
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        binding.webView.clearCache(true)
        loggedIn = false
        binding.controlsCard.visibility = View.GONE
        binding.instructionCard.visibility = View.VISIBLE
        updateWebViewVisibility()
        binding.webView.loadUrl("https://web.whatsapp.com")
        main.postDelayed({ pollLoginState() }, 2000)
    }

    /**
     * Types each phone number into WhatsApp's own search box using real
     * Android KeyEvents (not JS-simulated ones) and clicks the result the
     * same way a person would. This stays inside the same page — no full
     * reload per contact — so it runs about as fast as the existing-chats
     * flow. Real (trusted) key events matter here: WhatsApp's search input
     * is a React-controlled field that stops reacting to JS-dispatched
     * "input" events after the first search in a session, but genuine
     * OS-level keystrokes go through the normal input pipeline every time.
     */
    private fun startMissingSearch() {
        val resuming = missingQueue.isNotEmpty() && missingIndex < missingQueue.size
        deleteMissingEnabled = binding.deleteMissingPhotoSwitch.isChecked
        if (!resuming) {
            val onlyMissingPhoto = binding.onlyMissingPhotoSwitch.isChecked
            val contacts = repo.loadContacts()
            missingQueue = contacts
                .filter { !onlyMissingPhoto || !it.hasPhoto }
                .map { repo.normalize(it.phone) }
                .filter { it.length >= 7 }
                .distinct()
            missingIndex = 0
            foundCount = 0
            noPhotoCount = 0
            deletedCount = 0
            notOnWhatsappCount = 0
            errorCount = 0
            updateStats()
        }
        missingSearchActive = true
        binding.runMissingButton.text = "Caută contactele"
        setRunningUI(true)
        binding.webView.evaluateJavascript("window.__waStop = false;", null)
        SyncForegroundService.start(this)
        updateProgress(if (resuming) "Reiau căutarea..." else "Se caută...", missingIndex, missingQueue.size)
        processNextMissing()
    }

    private fun processNextMissing() {
        if (!missingSearchActive) return
        if (missingIndex >= missingQueue.size) {
            updateProgress("Gata.", missingIndex, missingQueue.size)
            missingSearchActive = false
            SyncForegroundService.stop(this)
            setRunningUI(false)
            return
        }
        val phone = missingQueue[missingIndex++]
        updateProgress("Caut...", missingIndex, missingQueue.size)
        appendLog("[$phone] (${missingIndex}/${missingQueue.size}) caut...")
        binding.webView.evaluateJavascript(FOCUS_SEARCH_SCRIPT) { result ->
            if (!missingSearchActive) return@evaluateJavascript
            if (result?.trim('"') != "true") {
                appendLog("[$phone]: nu am găsit căsuța de căutare")
                main.postDelayed({ processNextMissing() }, 400)
                return@evaluateJavascript
            }
            typeIntoWebView("+$phone")
            main.postDelayed({ searchAndExtract(phone) }, 1200)
        }
    }

    private fun searchAndExtract(phone: String) {
        if (!missingSearchActive) return
        val escapedPhone = phone.replace("\\", "\\\\").replace("'", "\\'")
        binding.webView.evaluateJavascript(
            "window.__waCurrentPhone = '$escapedPhone';$SEARCH_RESULT_SCRIPT", null
        )
        main.postDelayed({ clearSearchAndNext() }, 2500)
    }

    private fun clearSearchAndNext() {
        if (!missingSearchActive) return
        binding.webView.evaluateJavascript(CLEAR_SEARCH_SCRIPT) {
            main.postDelayed({ processNextMissing() }, 500)
        }
    }

    private fun typeIntoWebView(text: String) {
        binding.webView.requestFocus()
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        events?.forEach { binding.webView.dispatchKeyEvent(it) }
    }

    private fun updateProgress(status: String, current: Int, total: Int) {
        runOnUiThread {
            binding.progressText.text = if (total > 0) "$status  ($current/$total)" else status
            binding.progressBar.max = if (total > 0) total else 1
            binding.progressBar.progress = current
        }
        SyncForegroundService.updateProgress(status, current, total)
    }

    private fun statsLine(): String =
        "Găsite: $foundCount · Fără poză: $noPhotoCount · Șterse: $deletedCount · Fără WhatsApp: $notOnWhatsappCount · Erori: $errorCount"

    private fun updateStats() {
        val line = statsLine()
        runOnUiThread { binding.statsText.text = line }
        SyncForegroundService.updateStats(line)
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            if (logLines.isNotEmpty()) logLines.append('\n')
            logLines.append(message)
            binding.logView.text = logLines.toString()
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun savePhoto(phone: String, base64Jpeg: String): Boolean {
            return try {
                val bytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
                if (bytes.size < 500) {
                    appendLog("[$phone]: captură prea mică, sărită")
                    errorCount++
                    updateStats()
                    return false
                }
                val normalized = repo.normalize(phone)
                val matches = repo.loadContacts().filter { repo.normalize(it.phone) == normalized }
                if (matches.isEmpty()) {
                    appendLog("[$phone]: fără contact potrivit în agendă")
                    errorCount++
                    updateStats()
                    return false
                }
                for (m in matches) repo.setPhoto(m.contactId, bytes)
                foundCount++
                appendLog("[$phone]: poză găsită și salvată (${matches.size} contact(e))")
                updateStats()
                true
            } catch (e: Exception) {
                appendLog("[$phone]: eroare — ${e.message}")
                errorCount++
                updateStats()
                false
            }
        }

        @JavascriptInterface
        fun noPhoto(phone: String) {
            noPhotoCount++
            if (!deleteMissingEnabled) {
                appendLog("[$phone]: fără poză pe WhatsApp")
                updateStats()
                return
            }
            val normalized = repo.normalize(phone)
            val matches = repo.loadContacts().filter { repo.normalize(it.phone) == normalized && it.hasPhoto }
            if (matches.isEmpty()) {
                appendLog("[$phone]: fără poză pe WhatsApp")
                updateStats()
                return
            }
            for (m in matches) repo.deletePhoto(m.contactId)
            deletedCount++
            appendLog("[$phone]: fără poză pe WhatsApp — poza ștearsă (${matches.size} contact(e))")
            updateStats()
        }

        @JavascriptInterface
        fun notOnWhatsapp(phone: String) {
            notOnWhatsappCount++
            appendLog("[$phone]: nu are cont WhatsApp")
            updateStats()
        }

        @JavascriptInterface
        fun log(message: String) {
            appendLog(message)
        }
    }

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        /** True once the chat list (left sidebar) is present — i.e. this device is linked. */
        private const val LOGIN_CHECK_SCRIPT = "(function(){ return !!document.querySelector('#side'); })();"

        // Shared helpers reused by the scripts below. WhatsApp Web's avatar
        // is neither a plain <img> nor a simple CSS background in all cases —
        // it can be an SVG <image> with a real CDN URL (best quality) or a
        // <div> with a base64 data-URL background (lower-res placeholder).
        private val JS_HELPERS = """
function fullClick(el) {
  const rect = el.getBoundingClientRect();
  const x = rect.left + rect.width/2, y = rect.top + rect.height/2;
  const opts = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
  el.dispatchEvent(new PointerEvent('pointerdown', opts));
  el.dispatchEvent(new MouseEvent('mousedown', opts));
  el.dispatchEvent(new PointerEvent('pointerup', opts));
  el.dispatchEvent(new MouseEvent('mouseup', opts));
  el.dispatchEvent(new MouseEvent('click', opts));
}
function wait(ms){ return new Promise(r=>setTimeout(r,ms)); }
async function waitFor(check, timeoutMs, stepMs){
  timeoutMs = timeoutMs || 4000; stepMs = stepMs || 200;
  const start=Date.now();
  while(Date.now()-start<timeoutMs){ const v=check(); if(v) return v; await wait(stepMs); }
  return null;
}
function sanitizePhone(text){
  const c = text.replace(/[^\d+]/g,'');
  return c.length>=7 ? c : null;
}
function findPhoneInPanel(){
  const panel = document.querySelector('[data-testid="drawer-right"]');
  if(!panel) return null;
  const lines = panel.innerText.split('\n');
  for (const l of lines){
    const t = l.trim();
    if (/^\+?[\d\s()-]{8,20}${'$'}/.test(t) && /\d{6,}/.test(t.replace(/\D/g,''))) return t;
  }
  return null;
}
function findAvatarUrl(){
  const panel = document.querySelector('[data-testid="drawer-right"]');
  if(!panel) return null;
  const panelTop = panel.getBoundingClientRect().top;
  const svgImgs = panel.querySelectorAll('image');
  for (const svgImg of svgImgs){
    const r = svgImg.getBoundingClientRect();
    if (r.top - panelTop > 260 || r.width < 40) continue;
    const href = svgImg.getAttribute('href') || svgImg.getAttributeNS('http://www.w3.org/1999/xlink','href');
    if (href && href.startsWith('http')) return href;
  }
  const divs = panel.querySelectorAll('div');
  for (const el of divs){
    const r = el.getBoundingClientRect();
    if (r.top - panelTop > 260 || r.width < 40) continue;
    const bg = getComputedStyle(el).backgroundImage;
    const m = bg && bg.match(/url\("(data:image\/[^"]+)"\)/);
    if (m) return m[1];
  }
  return null;
}
async function toBase64(url){
  if (url.startsWith('data:')) return url.slice(url.indexOf(',')+1);
  const res = await fetch(url);
  const blob = await res.blob();
  const dataUrl = await new Promise(resolve => {
    const fr = new FileReader();
    fr.onload = () => resolve(fr.result);
    fr.readAsDataURL(blob);
  });
  return dataUrl.slice(dataUrl.indexOf(',')+1);
}
function closeOverlay(){
  document.body.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
}
function findConvHeader(){
  const headers = Array.from(document.querySelectorAll('header'));
  return headers.find(h => h.getAttribute('data-testid')!=='chatlist-header' && h.querySelector('img'));
}
"""

        /** Clicks the search box so real Android KeyEvents land in it. */
        private val FOCUS_SEARCH_SCRIPT = """
(function(){
  const box = document.querySelector('[data-testid="chat-list-search-container"] input') ||
    document.querySelector('input[aria-label="Search or start a new chat"]');
  if(!box) return false;
  const rect = box.getBoundingClientRect();
  const x = rect.left + rect.width/2, y = rect.top + rect.height/2;
  const opts = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
  box.dispatchEvent(new PointerEvent('pointerdown', opts));
  box.dispatchEvent(new MouseEvent('mousedown', opts));
  box.dispatchEvent(new PointerEvent('pointerup', opts));
  box.dispatchEvent(new MouseEvent('mouseup', opts));
  box.dispatchEvent(new MouseEvent('click', opts));
  box.focus();
  return true;
})();
"""

        /** Clicks the first search result and extracts its avatar, same as an existing-chat click. */
        private val SEARCH_RESULT_SCRIPT = """
(async function(){
$JS_HELPERS
  try {
    const cell = document.querySelector('#side [data-testid="cell-frame-container"]');
    if(!cell){ AndroidBridge.notOnWhatsapp(window.__waCurrentPhone || ''); return; }
    fullClick(cell);
    const convHeader = await waitFor(findConvHeader, 3000, 200);
    if(!convHeader){ AndroidBridge.log('nu s-a deschis conversația'); return; }
    fullClick(convHeader.querySelector('span[dir="auto"]') || convHeader.querySelector('img'));
    const panel = await waitFor(() => {
      const p = document.querySelector('[data-testid="drawer-right"]');
      return (p && p.innerText.trim().length>0) ? p : null;
    }, 3000);
    if(!panel){ AndroidBridge.log('fără panou info'); closeOverlay(); return; }
    const phoneText = findPhoneInPanel();
    const phone = phoneText ? sanitizePhone(phoneText) : null;
    if(!phone){ AndroidBridge.log('nu am găsit numărul în panou'); closeOverlay(); return; }
    const url = await waitFor(findAvatarUrl, 2500, 250);
    if(!url){ AndroidBridge.noPhoto(phone); closeOverlay(); return; }
    const b64 = await toBase64(url);
    AndroidBridge.savePhoto(phone, b64);
    closeOverlay();
  } catch(e){
    AndroidBridge.log('eroare: ' + e.message);
  }
})();
"""

        /** Clicks the search box's own "clear" (X) button to reset for the next number. */
        private val CLEAR_SEARCH_SCRIPT = """
(function(){
  const container = document.querySelector('[data-testid="chat-list-search-container"]');
  const btn = container ? container.querySelector('button, [role="button"]') : null;
  if(btn){
    const rect = btn.getBoundingClientRect();
    const x = rect.left + rect.width/2, y = rect.top + rect.height/2;
    const opts = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
    btn.dispatchEvent(new PointerEvent('pointerdown', opts));
    btn.dispatchEvent(new MouseEvent('mousedown', opts));
    btn.dispatchEvent(new PointerEvent('pointerup', opts));
    btn.dispatchEvent(new MouseEvent('mouseup', opts));
    btn.dispatchEvent(new MouseEvent('click', opts));
  }
  document.body.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
  return true;
})();
"""
    }
}
