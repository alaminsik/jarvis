package com.jarvis.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ================= অ্যাক্সেসিবিলিটি: স্ক্রিন কন্ট্রোল ================= */
class JarvisAccess : AccessibilityService() {
    companion object { @Volatile var i: JarvisAccess? = null }

    override fun onServiceConnected() { i = this }
    override fun onUnbind(intent: Intent?): Boolean { i = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun click(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            var c: AccessibilityNodeInfo? = n
            while (c != null) {
                if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                c = c.parent
            }
        }
        return false
    }

    fun type(text: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    /** up=true: আঙুল নিচ থেকে ওপরে (পেজ নিচে যায়) */
    fun swipe(up: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val h = dm.heightPixels.toFloat()
        val p = Path()
        if (up) { p.moveTo(x, h * 0.75f); p.lineTo(x, h * 0.25f) }
        else { p.moveTo(x, h * 0.25f); p.lineTo(x, h * 0.75f) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build()
        dispatchGesture(g, null, null)
    }

    fun global(a: Int): Boolean = performGlobalAction(a)
}

/* ================= মূল সার্ভিস: শোনা + বলা + কমান্ড ================= */
class JarvisService : Service() {
    private lateinit var tts: TextToSpeech
    private var ttsOk = false
    private var sr: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private var speaking = false
    private var running = false
    private var awakeUntil = 0L
    private var torchOn = false
    private var listening = false
    private var listenStart = 0L

    companion object { @Volatile var lastHeard: String = "" }

    private val BASE_WAKE = listOf(
        "জার্ভিস", "জারভিস", "জার্বিস", "জারবিস", "জার্ভিশ", "জারভিশ", "জার্ভিজ", "জারভিজ", "jarvis"
    )

    private fun wakeWords(): List<String> {
        val extra = getSharedPreferences("jv", Context.MODE_PRIVATE).getString("names", "") ?: ""
        return BASE_WAKE + extra.split(",", "،").map { norm(it) }.filter { it.length >= 2 }
    }

    private val APPS: List<Pair<List<String>, String>> = listOf(
        listOf("ফেসবুক", "ফেইসবুক", "facebook") to "com.facebook.katana",
        listOf("ইউটিউব", "youtube") to "com.google.android.youtube",
        listOf("হোয়াটসঅ্যাপ", "হোয়াটসআপ", "whatsapp") to "com.whatsapp",
        listOf("মেসেঞ্জার", "messenger") to "com.facebook.orca",
        listOf("ইনস্টাগ্রাম", "instagram") to "com.instagram.android",
        listOf("টেলিগ্রাম", "telegram") to "org.telegram.messenger",
        listOf("জিমেইল", "gmail") to "com.google.android.gm",
        listOf("ক্রোম", "chrome") to "com.android.chrome",
        listOf("প্লে স্টোর", "play store") to "com.android.vending",
        listOf("গ্যালারি", "ফটোজ", "photos") to "com.google.android.apps.photos",
        listOf("ক্যালকুলেটর", "calculator") to "com.google.android.calculator",
        listOf("বিকাশ", "bkash") to "com.bkash.customerapp",
        listOf("নগদ", "nagad") to "com.konasl.nagad"
    )

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startFg()
        if (!running) { running = true; initTts(); startListen() }
        i?.getStringExtra("cmd")?.let { route(it, true) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { sr?.destroy() } catch (e: Exception) {}
        try { tts.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jv", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jv")
            .setContentTitle("জার্ভিস শুনছে")
            .setContentText("\"জার্ভিস\" বলে ডাকুন")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    /* ---------- মেয়ের কণ্ঠ ---------- */
    private fun initTts() {
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts.language = Locale("bn", "BD")
                tts.setPitch(1.3f)
                tts.setSpeechRate(0.92f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) { speaking = false; h.post { startListen() } }
                    override fun onError(u: String?) { speaking = false; h.post { startListen() } }
                })
                ttsOk = true
                say("জার্ভিস চালু হয়েছে")
            }
        }
    }

    private fun say(t: String) {
        if (!ttsOk) return
        speaking = true
        try { sr?.cancel() } catch (e: Exception) {}
        listening = false
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    /* ---------- শোনা ---------- */
    private fun restart(d: Long) { h.postDelayed({ startListen() }, d) }

    private fun startListen() {
        if (!running || speaking) return
        if (listening && System.currentTimeMillis() - listenStart < 12000) return
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this)
            sr!!.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    listening = false
                    val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!l.isNullOrEmpty()) {
                        val ww = wakeWords()
                        val pick = l.firstOrNull { c ->
                            val n = norm(c)
                            ww.any { n.contains(it) }
                        } ?: l[0]
                        lastHeard = pick
                        Toast.makeText(this@JarvisService, "শুনলাম: " + pick, Toast.LENGTH_SHORT).show()
                        route(pick, false)
                    }
                    restart(100)
                }
                override fun onError(e: Int) {
                    listening = false
                    restart(
                        when (e) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 100L
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                            else -> 500L
                        }
                    )
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listening = true
        listenStart = System.currentTimeMillis()
        try { sr!!.startListening(i) } catch (e: Exception) { listening = false; restart(1500) }
    }

    /* ---------- সাহায্যকারী ---------- */
    private fun norm(s: String): String {
        val d = "০১২৩৪৫৬৭৮৯"
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            val k = d.indexOf(c)
            sb.append(if (k >= 0) ('0' + k) else c)
        }
        return sb.toString().replace(Regex("[।,?!.]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun any(t: String, vararg w: String) = w.any { t.contains(it) }

    private val FILLER = listOf(
        "খোলো", "খুলো", "খুলুন", "ওপেন", "open", "চালু করো", "করো", "খোঁজো", "খুঁজো", "খোজো",
        "সার্চ", "search", "দেখাও", "চালাও", "প্লে", "দাও", "ইউটিউবে", "ইউটিউব", "youtube",
        "গুগলে", "গুগল", "google", "ম্যাপে", "ম্যাপ", "ক্লিক", "চাপ", "ট্যাপ", "চাপো",
        "লেখো", "লিখো", "টাইপ", "কল", "ফোন", "করে", "কর"
    ).sortedByDescending { it.length }

    private fun clean(t: String): String {
        var s = t
        for (f in FILLER) s = s.replace(f, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun open(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { say("খুলতে পারিনি") }
    }

    private fun openPkg(p: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(p) ?: return false
        open(i); return true
    }

    private fun openByLabel(q: String): Boolean {
        if (q.length < 2) return false
        val pm = packageManager
        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val m = pm.queryIntentActivities(li, 0).firstOrNull {
            val l = it.loadLabel(pm).toString().lowercase()
            l.contains(q) || (l.length > 2 && q.contains(l))
        } ?: return false
        val i = pm.getLaunchIntentForPackage(m.activityInfo.packageName) ?: return false
        open(i); return true
    }

    private fun contact(name: String): String? {
        if (name.isEmpty()) return null
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)?.lowercase() ?: continue
                if (n.contains(name)) return c.getString(1)
            }
        }
        return null
    }

    private fun call(t: String) {
        var num = t.filter { it.isDigit() || it == '+' }
        if (num.length < 5) num = contact(clean(t)) ?: ""
        if (num.isEmpty()) { say("কাকে কল করব? নম্বর বা নাম বলুন"); return }
        val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        say("কল করছি")
        open(Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))))
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
            torchOn = on
            say(if (on) "টর্চ জ্বালালাম" else "টর্চ নিভিয়েছি")
        } catch (e: Exception) { say("টর্চ চালানো গেল না") }
    }

    private fun vol(dir: Int, msg: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        say(msg)
    }

    private fun a11y(block: (JarvisAccess) -> Unit) {
        val a = JarvisAccess.i
        if (a == null) say("আগে অ্যাক্সেসিবিলিটি চালু করুন") else block(a)
    }

    private fun view(url: String, msg: String) {
        say(msg); open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun act(action: String, msg: String) {
        say(msg); open(Intent(action))
    }

    /* ---------- কমান্ড রাউটার ---------- */
    private fun route(raw: String, bypass: Boolean) {
        var t = norm(raw)
        val ww = wakeWords()
        val woke = ww.any { t.contains(it) }
        if (!bypass && !woke && System.currentTimeMillis() > awakeUntil) return
        for (w in ww) t = t.replace(w, " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        awakeUntil = 0
        if (t.isEmpty()) { awakeUntil = System.currentTimeMillis() + 8000; say("জি, বলুন"); return }

        when {
            any(t, "বন্ধ হও", "ঘুমাও", "থামো") -> {
                say("ঠিক আছে, বিদায়"); h.postDelayed({ stopSelf() }, 1800)
            }
            any(t, "কল করো", "কল দাও", "কল কর", "ফোন করো", "call") -> call(t)

            any(t, "পেছনে", "পিছনে", "ব্যাক") -> a11y { it.global(AccessibilityService.GLOBAL_ACTION_BACK); say("ঠিক আছে") }
            any(t, "হোম") -> a11y { it.global(AccessibilityService.GLOBAL_ACTION_HOME); say("হোম স্ক্রিনে এলাম") }
            any(t, "রিসেন্ট", "সব অ্যাপ") -> a11y { it.global(AccessibilityService.GLOBAL_ACTION_RECENTS); say("রিসেন্ট অ্যাপ") }
            any(t, "নোটিফিকেশন") -> a11y { it.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); say("নোটিফিকেশন") }
            any(t, "কুইক সেটিং") -> a11y { it.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); say("কুইক সেটিংস") }
            any(t, "স্ক্রিনশট") -> a11y {
                if (Build.VERSION.SDK_INT >= 28) { it.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); say("স্ক্রিনশট নিলাম") }
            }
            any(t, "স্ক্রিন বন্ধ", "ফোন লক", "লক করো") -> a11y {
                if (Build.VERSION.SDK_INT >= 28) { say("লক করছি"); it.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
            }
            any(t, "নিচে", "নিচে স্ক্রল") -> a11y { it.swipe(true); say("ঠিক আছে") }
            any(t, "ওপরে", "উপরে") -> a11y { it.swipe(false); say("ঠিক আছে") }
            any(t, "লেখো", "লিখো", "টাইপ") -> a11y {
                val x = clean(t)
                say(if (it.type(x)) "লিখলাম" else "লেখার ঘর পাইনি")
            }
            any(t, "ক্লিক", "চাপ দাও", "চাপো", "ট্যাপ") -> a11y {
                val x = clean(t)
                say(if (x.isNotEmpty() && it.click(x)) "ঠিক আছে" else "বোতামটা পাইনি")
            }

            any(t, "টর্চ", "ফ্ল্যাশ") -> torch(!any(t, "বন্ধ", "নেভাও", "অফ"))
            any(t, "ভলিউম বাড়াও", "আওয়াজ বাড়াও", "জোরে") -> vol(AudioManager.ADJUST_RAISE, "ভলিউম বাড়ালাম")
            any(t, "ভলিউম কমাও", "আওয়াজ কমাও", "আস্তে") -> vol(AudioManager.ADJUST_LOWER, "ভলিউম কমালাম")
            any(t, "মিউট", "চুপ") -> vol(AudioManager.ADJUST_MUTE, "মিউট করলাম")

            any(t, "ওয়াইফাই", "wifi", "ওয়াই-ফাই") -> act(Settings.ACTION_WIFI_SETTINGS, "ওয়াইফাই সেটিংস")
            any(t, "ব্লুটুথ", "bluetooth") -> act(Settings.ACTION_BLUETOOTH_SETTINGS, "ব্লুটুথ সেটিংস")
            any(t, "ব্রাইটনেস", "ডিসপ্লে", "উজ্জ্বলতা") -> act(Settings.ACTION_DISPLAY_SETTINGS, "ডিসপ্লে সেটিংস")
            any(t, "লোকেশন") -> act(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "লোকেশন সেটিংস")
            any(t, "ব্যাটারি") -> act(Intent.ACTION_POWER_USAGE_SUMMARY, "ব্যাটারি")
            any(t, "সাউন্ড") -> act(Settings.ACTION_SOUND_SETTINGS, "সাউন্ড সেটিংস")
            any(t, "সেটিং", "settings") -> act(Settings.ACTION_SETTINGS, "সেটিংস খুলছি")
            any(t, "ক্যামেরা", "camera") -> act("android.media.action.STILL_IMAGE_CAMERA", "ক্যামেরা খুলছি")

            any(t, "কয়টা", "কটা", "সময়", "বাজে") ->
                say("এখন সময় " + SimpleDateFormat("h:mm", Locale("bn", "BD")).format(Date()))
            any(t, "তারিখ", "কী বার", "আজকের দিন") ->
                say("আজ " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale("bn", "BD")).format(Date()))

            any(t, "ম্যাপ", "রাস্তা") -> {
                val q = clean(t)
                if (q.isEmpty()) { say("ম্যাপ খুলছি"); openPkg("com.google.android.apps.maps") }
                else view("geo:0,0?q=" + Uri.encode(q), "$q ম্যাপে খুঁজছি")
            }
            any(t, "ইউটিউবে", "youtube", "ইউটিউব") && any(t, "ইউটিউবে", "চালাও", "প্লে", "খোঁজো", "খুঁজো", "সার্চ") && clean(t).isNotEmpty() ->
                view("https://www.youtube.com/results?search_query=" + Uri.encode(clean(t)), clean(t) + " ইউটিউবে খুঁজছি")
            any(t, "গুগলে", "খোঁজো", "খুঁজো", "খোজো", "সার্চ", "search") && clean(t).isNotEmpty() ->
                view("https://www.google.com/search?q=" + Uri.encode(clean(t)), clean(t) + " খুঁজছি")

            APPS.any { (k, _) -> k.any { t.contains(it) } } -> {
                val (k, p) = APPS.first { (k, _) -> k.any { t.contains(it) } }
                if (openPkg(p)) say(k[0] + " খুলছি") else say(k[0] + " অ্যাপ নেই")
            }
            any(t, "খোলো", "খুলো", "ওপেন", "open", "চালু করো") -> {
                val q = clean(t)
                if (openByLabel(q)) say("$q খুলছি") else say("এই নামের অ্যাপ পাইনি")
            }

            any(t, "হ্যালো", "হাই", "কেমন আছো", "hello") -> say("হ্যালো! আমি জার্ভিস। বলুন, কী করতে পারি?")
            any(t, "তোমার নাম", "কে তুমি", "তুমি কে") -> say("আমি জার্ভিস, আপনার ফোনের সহকারী।")
            any(t, "ধন্যবাদ", "থ্যাঙ্ক") -> say("আপনাকে স্বাগতম!")
            else -> say("বুঝতে পারিনি, আবার বলুন")
        }
    }
}

/* ================= স্ক্রিন: চালু/বন্ধ ও অনুমতি ================= */
class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(40, 80, 40, 40)

        val title = TextView(this)
        title.text = "🤖 জার্ভিস"
        title.textSize = 30f
        l.addView(title)

        val info = TextView(this)
        info.text = "ধাপ ১ ও ২ একবার করে নিন, তারপর চালু করুন। এরপর \"জার্ভিস\" বলে ডাকুন, যেমন: জার্ভিস ফেসবুক খোলো।"
        info.setPadding(0, 20, 0, 20)
        l.addView(info)

        fun btn(t: String, f: () -> Unit) {
            val x = Button(this)
            x.text = t
            x.textSize = 17f
            x.setOnClickListener { f() }
            l.addView(x)
        }

        btn("১. অ্যাক্সেসিবিলিটি চালু করো") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("২. অন্য অ্যাপের ওপর দেখানোর অনুমতি") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("▶ জার্ভিস চালু করো") { askPerms() }
        btn("■ জার্ভিস বন্ধ করো") { stopService(Intent(this, JarvisService::class.java)) }

        val et = EditText(this)
        et.hint = "লিখে কমান্ড দিন"
        l.addView(et)
        btn("পাঠাও") {
            val c = et.text.toString().trim()
            if (c.isNotEmpty()) {
                startForegroundService(Intent(this, JarvisService::class.java).putExtra("cmd", c))
                et.setText("")
            }
        }

        val nameBox = EditText(this)
        nameBox.hint = "ডাকার নাম (বাংলায়; একাধিক বানান কমা দিয়ে)"
        nameBox.setText(getSharedPreferences("jv", Context.MODE_PRIVATE).getString("names", ""))
        l.addView(nameBox)
        btn("নাম সেভ করো") {
            getSharedPreferences("jv", Context.MODE_PRIVATE).edit()
                .putString("names", nameBox.text.toString()).apply()
            Toast.makeText(this, "নাম সেভ হয়েছে", Toast.LENGTH_SHORT).show()
        }

        val heard = TextView(this)
        heard.textSize = 16f
        heard.setPadding(0, 30, 0, 0)
        l.addView(heard)
        val hh = Handler(Looper.getMainLooper())
        hh.post(object : Runnable {
            override fun run() {
                heard.text = "শেষ শোনা কথা: " + JarvisService.lastHeard
                hh.postDelayed(this, 700)
            }
        })

        val sv = ScrollView(this)
        sv.addView(l)
        setContentView(sv)
    }

    private fun askPerms() {
        val p = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, JarvisService::class.java))
        } else {
            Toast.makeText(this, "মাইকের অনুমতি দিন", Toast.LENGTH_LONG).show()
        }
    }

}
