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
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
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
    private var searchGeneration = 0

    private var loggedIn = false
    private var debugMode = false
    @Volatile private var deleteMissingEnabled = false
    private var webViewInOverlay = false
    private var overlayPromptShown = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* if denied, the sync still runs, it just won't show a notification */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ContactRepository(this)

        // We type into WhatsApp's search box via real Android KeyEvents, not
        // the on-screen keyboard — letting the IME pop up anyway would both
        // be a visible annoyance and, worse, resize the WebView's viewport
        // (adjustResize) mid-search, shifting every element's coordinates
        // right as we're clicking them.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

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

        // Deleting only makes sense when contacts that already have a photo
        // are actually searched — "only missing photo" would filter them out.
        binding.deleteMissingPhotoSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && binding.onlyMissingPhotoSwitch.isChecked) {
                binding.onlyMissingPhotoSwitch.isChecked = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncForegroundService.stopCallback = null
    }

    /**
     * When the Activity comes back to the foreground, pull the WebView back
     * out of the background overlay (if it was moved there) and into our
     * own layout.
     */
    override fun onStart() {
        super.onStart()
        if (webViewInOverlay) {
            SyncForegroundService.moveOutOfOverlay(binding.webView)
            binding.webViewContainer.addView(
                binding.webView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            webViewInOverlay = false
            updateWebViewVisibility()
        }
    }

    /**
     * When the Activity leaves the foreground (Home pressed, screen locked)
     * while a search is running, move the WebView into a real overlay
     * window owned by the foreground service, so it keeps rendering and
     * running JS instead of being throttled the moment our window is gone.
     * Requires the "draw over other apps" permission; if it isn't granted,
     * this is a no-op and the sync simply pauses until the app is reopened.
     */
    override fun onStop() {
        super.onStop()
        if (missingSearchActive && loggedIn && SyncForegroundService.hasOverlayPermission(this)) {
            if (SyncForegroundService.moveToOverlay(binding.webView)) {
                webViewInOverlay = true
            }
        }
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
                promptOverlayPermissionOnce()
            } else {
                main.postDelayed({ pollLoginState() }, 2000)
            }
        }
    }

    private fun promptOverlayPermissionOnce() {
        if (overlayPromptShown || SyncForegroundService.hasOverlayPermission(this)) return
        overlayPromptShown = true
        AlertDialog.Builder(this)
            .setTitle("Rulare cu ecranul stins")
            .setMessage(
                "Ca să continue căutarea și cu telefonul blocat sau cu altă aplicație deschisă, " +
                    "activează permisiunea \"Afișare peste alte aplicații\" pentru această aplicație. " +
                    "Fără ea, sincronizarea se oprește temporar cât timp nu ești în aplicație."
            )
            .setPositiveButton("Activează") { _, _ -> SyncForegroundService.requestOverlayPermission(this) }
            .setNegativeButton("Nu acum", null)
            .show()
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
        val gen = ++searchGeneration
        updateProgress("Caut...", missingIndex, missingQueue.size)
        appendLog("[$phone] (${missingIndex}/${missingQueue.size}) caut...")
        binding.webView.evaluateJavascript(FOCUS_SEARCH_SCRIPT) { result ->
            if (!missingSearchActive || gen != searchGeneration) return@evaluateJavascript
            val json = runCatching { JSONObject(result ?: "{}") }.getOrElse { JSONObject() }
            if (!json.optBoolean("found", false)) {
                appendLog("[$phone]: nu am găsit căsuța de căutare")
                main.postDelayed({ processNextMissing() }, 400)
                return@evaluateJavascript
            }
            val baseline = json.optString("baseline", "")
            typeIntoWebView("+$phone")
            main.postDelayed({ searchAndExtract(phone, baseline, gen) }, 400)
        }
    }

    /**
     * Runs the click+extract script only after confirming the search actually
     * filtered the list (comparing against the "before typing" signature) —
     * without this check, a slow-to-filter search would make the code click
     * whatever happened to be on top of the still-unfiltered list (often the
     * Archived section), which is both wasted work and wrong data.
     */
    private fun searchAndExtract(phone: String, baseline: String, gen: Int) {
        if (!missingSearchActive || gen != searchGeneration) return
        val script =
            "window.__waCurrentPhone = '${jsEscape(phone)}'; " +
                "window.__waBaseline = '${jsEscape(baseline)}'; " +
                "window.__waGen = $gen;$SEARCH_RESULT_SCRIPT"
        binding.webView.evaluateJavascript(script, null)
        // Safety net only — the script itself signals completion via
        // AndroidBridge.searchDone(), which normally advances well before this.
        main.postDelayed({ advanceIfStillCurrent(gen) }, 24000)
    }

    private fun advanceIfStillCurrent(gen: Int) {
        if (!missingSearchActive || gen != searchGeneration) return
        clearSearchAndNext()
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

    private fun jsEscape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

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
        fun searchFailed(phone: String) {
            errorCount++
            appendLog("[$phone]: căutarea nu s-a filtrat la timp, sărit")
            updateStats()
        }

        @JavascriptInterface
        fun log(message: String) {
            appendLog(message)
        }

        @JavascriptInterface
        fun searchDone(gen: Int) {
            main.post { advanceIfStillCurrent(gen) }
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
  return document.querySelector('[data-testid="conversation-header"]');
}
function findProfileButton(header){
  return header.querySelector('[aria-label="Profile details"], [title="Profile details"]') || header;
}
function listSignature(){
  const cells = document.querySelectorAll('#side [data-testid="cell-frame-container"]');
  const names = [];
  for (let i=0;i<Math.min(cells.length,3);i++){ names.push(cells[i].innerText.split('\n')[0]); }
  return names.join('|') + '#' + cells.length;
}
"""

        /** Clicks the search box and records the list's current contents, so a later check can tell whether the search actually filtered it. */
        private val FOCUS_SEARCH_SCRIPT = """
(function(){
$JS_HELPERS
  const box = document.querySelector('[data-testid="chat-list-search-container"] input') ||
    document.querySelector('input[aria-label="Search or start a new chat"]');
  if(!box) return {found:false};
  const rect = box.getBoundingClientRect();
  const x = rect.left + rect.width/2, y = rect.top + rect.height/2;
  const opts = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
  box.dispatchEvent(new PointerEvent('pointerdown', opts));
  box.dispatchEvent(new MouseEvent('mousedown', opts));
  box.dispatchEvent(new PointerEvent('pointerup', opts));
  box.dispatchEvent(new MouseEvent('mouseup', opts));
  box.dispatchEvent(new MouseEvent('click', opts));
  box.focus();
  return {found:true, baseline: listSignature()};
})();
"""

        /**
         * Waits until the list's signature actually differs from the
         * pre-typing baseline (i.e. the search really filtered it) before
         * clicking anything. Without this, a slow-to-filter search would
         * make the code click whatever was on top of the still-unfiltered
         * list — usually the Archived section — instead of the real result.
         */
        private val SEARCH_RESULT_SCRIPT = """
(async function(){
$JS_HELPERS
  try {
    const p0 = window.__waCurrentPhone || '';
    AndroidBridge.log('[' + p0 + '] pas 1: aștept filtrarea listei...');
    const changed = await waitFor(() => {
      const sig = listSignature();
      return sig !== window.__waBaseline ? sig : null;
    }, 7000, 200);
    if(!changed){ AndroidBridge.log('[' + p0 + '] EȘEC pas 1: lista nu s-a filtrat'); AndroidBridge.searchFailed(p0); return; }
    AndroidBridge.log('[' + p0 + '] pas 1 OK: ' + changed);
    const cell = document.querySelector('#side [data-testid="cell-frame-container"]');
    if(!cell){ AndroidBridge.log('[' + p0 + '] fără rezultat în listă'); AndroidBridge.notOnWhatsapp(p0); return; }

    // The conversation header is a persistent DOM node reused across chats —
    // it exists from the very first chat opened in the session onward, so
    // just waiting for it to "exist" resolves instantly and races ahead of
    // React actually rendering the newly clicked contact. Wait for its text
    // to actually change instead.
    const headerBefore = findConvHeader();
    const headerBeforeText = headerBefore ? headerBefore.innerText : '';
    fullClick(cell);
    AndroidBridge.log('[' + p0 + '] pas 2: am dat click pe rezultat, aștept antetul...');
    const convHeader = await waitFor(() => {
      const h = findConvHeader();
      return (h && h.innerText.trim().length>0 && h.innerText !== headerBeforeText) ? h : null;
    }, 6000, 200);
    if(!convHeader){ AndroidBridge.log('[' + p0 + '] EȘEC pas 2: antetul nu s-a schimbat (era: "' + headerBeforeText.slice(0,30) + '")'); AndroidBridge.searchFailed(p0); return; }
    AndroidBridge.log('[' + p0 + '] pas 2 OK: ' + convHeader.innerText.split('\n')[0]);
    fullClick(findProfileButton(convHeader));
    AndroidBridge.log('[' + p0 + '] pas 3: am dat click pe profil, aștept panoul...');
    const panel = await waitFor(() => {
      const p = document.querySelector('[data-testid="drawer-right"]');
      return (p && p.innerText.trim().length>0) ? p : null;
    }, 4000);
    if(!panel){ AndroidBridge.log('[' + p0 + '] EȘEC pas 3: panoul de info nu s-a deschis'); AndroidBridge.searchFailed(p0); closeOverlay(); return; }
    AndroidBridge.log('[' + p0 + '] pas 3 OK: panou deschis');
    const phoneText = findPhoneInPanel();
    const phone = phoneText ? sanitizePhone(phoneText) : null;
    if(!phone){ AndroidBridge.log('[' + p0 + '] EȘEC pas 4: nu am găsit numărul în panou (text: "' + panel.innerText.slice(0,60).replace(/\n/g,' ') + '")'); AndroidBridge.searchFailed(p0); closeOverlay(); return; }
    AndroidBridge.log('[' + p0 + '] pas 4 OK: numărul din panou = ' + phone);
    const url = await waitFor(findAvatarUrl, 3500, 250);
    if(!url){ AndroidBridge.log('[' + p0 + '] pas 5: fără poză găsită'); AndroidBridge.noPhoto(phone); closeOverlay(); return; }
    AndroidBridge.log('[' + p0 + '] pas 5 OK: poză găsită, salvez...');
    const b64 = await toBase64(url);
    AndroidBridge.savePhoto(phone, b64);
    closeOverlay();
  } catch(e){
    AndroidBridge.searchFailed(window.__waCurrentPhone || '');
    AndroidBridge.log('eroare: ' + e.message);
  } finally {
    AndroidBridge.searchDone(window.__waGen);
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
