package ro.bara.whatsappcontactphotosync

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private var updated = 0
    private var skipped = 0

    private var missingQueue: List<String> = emptyList()
    private var missingIndex = 0
    private var waitingForMissingLoad = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ContactRepository(this)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_USER_AGENT
        }
        binding.webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (waitingForMissingLoad) {
                    waitingForMissingLoad = false
                    // Give the SPA time to render the opened chat before we
                    // start poking at its DOM.
                    main.postDelayed({ extractCurrentChat() }, 3000)
                }
            }
        }
        binding.webView.loadUrl("https://web.whatsapp.com")

        binding.runExistingButton.setOnClickListener {
            appendLog("Pornesc extragerea din conversațiile existente...")
            binding.webView.evaluateJavascript(EXISTING_CHATS_SCRIPT, null)
        }

        binding.runMissingButton.setOnClickListener {
            startMissingSearch()
        }
    }

    private fun startMissingSearch() {
        val contacts = repo.loadContacts()
        missingQueue = contacts.map { repo.normalize(it.phone) }.filter { it.length >= 7 }.distinct()
        missingIndex = 0
        appendLog("Caut ${missingQueue.size} numere (inclusiv fără conversație existentă)...")
        processNextMissing()
    }

    private fun processNextMissing() {
        if (missingIndex >= missingQueue.size) {
            appendLog("Gata căutarea. Actualizate: $updated · Omise: $skipped")
            return
        }
        val phone = missingQueue[missingIndex++]
        appendLog("[$phone] (${missingIndex}/${missingQueue.size}) deschid...")
        waitingForMissingLoad = true
        binding.webView.loadUrl("https://web.whatsapp.com/send?phone=$phone")
    }

    private fun extractCurrentChat() {
        binding.webView.evaluateJavascript(EXTRACT_CURRENT_SCRIPT, null)
        main.postDelayed({ processNextMissing() }, 5000)
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
                    skipped++
                    return false
                }
                val normalized = repo.normalize(phone)
                val matches = repo.loadContacts().filter { repo.normalize(it.phone) == normalized }
                if (matches.isEmpty()) {
                    appendLog("[$phone]: fără contact potrivit în agendă")
                    skipped++
                    return false
                }
                for (m in matches) repo.setPhoto(m.contactId, bytes)
                updated++
                appendLog("[$phone]: poză salvată (${matches.size} contact(e))")
                true
            } catch (e: Exception) {
                appendLog("[$phone]: eroare — ${e.message}")
                skipped++
                false
            }
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

        // Shared helpers reused by both scripts below. WhatsApp Web's avatar
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
async function scrollToLoadAll(maxRounds){
  maxRounds = maxRounds || 60;
  const listEl = document.querySelector('[data-testid="chat-list"], [aria-label][role="grid"]');
  if(!listEl) return;
  let last=-1;
  for(let i=0;i<maxRounds;i++){
    const c = document.querySelectorAll('[data-testid="cell-frame-container"]').length;
    if(c===last) break;
    last=c;
    listEl.scrollTop = listEl.scrollHeight;
    await wait(400);
  }
}
function findConvHeader(){
  const headers = Array.from(document.querySelectorAll('header'));
  return headers.find(h => h.getAttribute('data-testid')!=='chatlist-header' && h.querySelector('img'));
}
"""

        /** Iterates every existing chat in the sidebar list. */
        private val EXISTING_CHATS_SCRIPT = """
(async function(){
$JS_HELPERS
  await scrollToLoadAll();
  const allCells = Array.from(document.querySelectorAll('[data-testid="cell-frame-container"]'));
  AndroidBridge.log('Găsite ' + allCells.length + ' conversații.');

  for (let i=0;i<allCells.length;i++){
    const cell = allCells[i];
    const titleEl = cell.querySelector('span[dir="auto"][title]');
    const name = titleEl ? titleEl.getAttribute('title') : '(?)';
    try {
      fullClick(cell);
      await wait(900);
      const convHeader = findConvHeader();
      if(!convHeader) continue;
      fullClick(convHeader.querySelector('span[dir="auto"]') || convHeader.querySelector('img'));
      const panel = await waitFor(() => {
        const p = document.querySelector('[data-testid="drawer-right"]');
        return (p && p.innerText.trim().length>0) ? p : null;
      }, 3000);
      if(!panel){ closeOverlay(); await wait(250); continue; }
      const phoneText = findPhoneInPanel();
      const phone = phoneText ? sanitizePhone(phoneText) : null;
      if(!phone){ closeOverlay(); await wait(250); continue; }
      const url = await waitFor(findAvatarUrl, 2500, 250);
      if(!url){ closeOverlay(); await wait(250); continue; }
      const b64 = await toBase64(url);
      AndroidBridge.savePhoto(phone, b64);
      closeOverlay(); await wait(300);
    } catch(e){
      AndroidBridge.log('[' + name + ']: eroare ' + e.message);
      closeOverlay(); await wait(250);
    }
  }
  AndroidBridge.log('Gata parcurgerea conversațiilor existente.');
})();
"""

        /** Run once after navigating to web.whatsapp.com/send?phone=XXXX. */
        private val EXTRACT_CURRENT_SCRIPT = """
(async function(){
$JS_HELPERS
  try {
    const convHeader = await waitFor(findConvHeader, 4000, 200);
    if(!convHeader){ AndroidBridge.log('nu s-a deschis niciun chat (probabil nu e pe WhatsApp)'); return; }
    fullClick(convHeader.querySelector('span[dir="auto"]') || convHeader.querySelector('img'));
    const panel = await waitFor(() => {
      const p = document.querySelector('[data-testid="drawer-right"]');
      return (p && p.innerText.trim().length>0) ? p : null;
    }, 3000);
    if(!panel){ AndroidBridge.log('nu s-a deschis panoul de informații'); return; }
    const phoneText = findPhoneInPanel();
    const phone = phoneText ? sanitizePhone(phoneText) : null;
    if(!phone){ AndroidBridge.log('nu am găsit numărul în panou'); return; }
    const url = await waitFor(findAvatarUrl, 2500, 250);
    if(!url){ AndroidBridge.log('[' + phone + ']: fără poză'); return; }
    const b64 = await toBase64(url);
    AndroidBridge.savePhoto(phone, b64);
  } catch(e){
    AndroidBridge.log('eroare: ' + e.message);
  }
})();
"""
    }
}
