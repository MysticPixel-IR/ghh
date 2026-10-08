package app.minevoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Notification
import android.graphics.Bitmap
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.pm.PackageInfoCompat
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

enum class St { Idle, Connecting, Connected, Reconnecting, NeedsLink }

data class GroupInfo(val id: String, val n: Int, val lock: Boolean, val closed: Boolean)
data class AdmUser(val name: String, val group: String, val inGame: Boolean, val muted: Boolean, val admin: Boolean, val udp: Boolean)
data class AdmGroup(val id: String, val members: List<String>, val closed: Boolean, val lock: Boolean)
data class LogEntry(val t: Long, val s: String)
data class TopEntry(val n: String, val ms: Long)
data class Adm(val users: List<AdmUser> = emptyList(), val groups: List<AdmGroup> = emptyList(), val bans: List<String> = emptyList(), val log: List<LogEntry> = emptyList())

data class UpdateInfo(val code: Int, val name: String, val notes: String, val apk: String, val force: Boolean)

data class Ui(
    val state: St = St.Idle, val name: String = "", val msg: String = "",
    val muted: Boolean = false, val staffMuted: Boolean = false,
    val speaker: Boolean = true, val ptt: Boolean = false, val pttDown: Boolean = false,
    val micGain: Float = 1f, val outGain: Float = 1f, val udp: Boolean = false,
    val group: String = "", val speaking: List<String> = emptyList(), val talking: Boolean = false,
    val overlay: Boolean = false, val compact: Boolean = false, val scale: Float = 1f,
    val inGame: Boolean = false,
    val admin: Boolean = false, val groups: List<GroupInfo> = emptyList(), val openGroups: Boolean = false,
    val adm: Adm = Adm(), val notice: String = "", val noticeId: Int = 0,
    val vad: Float = 0.6f, val level: Float = 0f, val stereo: Boolean = true,
    val ping: Int = -1, val update: UpdateInfo? = null,
    val deaf: Boolean = false, val bgOk: Boolean = true, val vols: Map<String, Float> = emptyMap(), val seen: List<String> = emptyList(),
    val avatar: String = "", val members: List<String> = emptyList(), val membersOff: List<String> = emptyList(),
    val ovTeam: Boolean = true, val ovOthers: Boolean = true, val groupSince: Long = 0L, val connSince: Long = 0L,
    val top: List<TopEntry> = emptyList(), val myTalkMs: Long = 0L, val myRank: Int = 0
)

/** حساسیت ۰..۱ ← آستانه‌ی تشخیص صدا (هرچه حساسیت بیشتر، آستانه کمتر) */
fun vadThreshold(sens: Float): Float = 100f + 2400f * (1f - sens) * (1f - sens)

private fun JSONObject.toUpdate() = UpdateInfo(optInt("code"), optString("name"), optString("notes"), optString("apk"), optBoolean("force", true))

private fun JSONObject.strs(k: String): List<String> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

private const val RATE = 16000
private const val FRAME = 320 // ۲۰ میلی‌ثانیه
private const val ITD_MAX = 10 // بیشینه‌ی تأخیر بین دو گوش (≈۰٫۶ms در ۱۶kHz)
private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** سرویس پیش‌زمینه: اتصال، ضبط، Opus، UDP رمزشده با مسیر جایگزین TCP، وصل مجدد */
class VoiceService : Service() {
    companion object {
        const val START = "start"; const val STOP = "stop"; const val MUTE = "mute"; const val SPK = "spk"
        const val GROUP = "group"; const val OVERLAY = "overlay"; const val SET = "set"
        const val ADM = "adm"; const val PTT = "ptt"; const val DEAF = "deaf"; const val TOP = "top"; const val INVREPLY = "invreply"; const val RECONNECT = "reconnect"
        val ui = MutableStateFlow(Ui())
        /** آیا برنامه از محدودیت باتری مستثنا است (برای ماندن ویس در پس‌زمینه) */
        fun bgOk(c: Context): Boolean = if (Build.VERSION.SDK_INT >= 23)
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName) else true

        fun load(c: Context): Ui {
            val p = c.getSharedPreferences("v", 0)
            return Ui(bgOk = bgOk(c), speaker = p.getBoolean("spk", true), ptt = p.getBoolean("ptt", false),
                micGain = p.getFloat("mic", 1f), outGain = p.getFloat("out", 1f),
                overlay = p.getBoolean("overlay", false), compact = p.getBoolean("compact", false),
                scale = p.getFloat("scale", 1f),
                ovTeam = p.getBoolean("ovTeam", true), ovOthers = p.getBoolean("ovOthers", true),
                vad = p.getFloat("vad", 0.6f), stereo = p.getBoolean("stereo", true),
                vols = runCatching { JSONObject(p.getString("vols", "{}") ?: "{}").let { o -> o.keys().asSequence().associateWith { k -> o.getDouble(k).toFloat() } } }.getOrDefault(emptyMap<String, Float>()).mapKeys { (k, _) -> k.lowercase() },
                seen = runCatching { JSONArray(p.getString("seen", "[]") ?: "[]").let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList()))
        }
        fun cmd(c: Context, a: String, vararg e: Pair<String, String>) {
            val i = Intent(c, VoiceService::class.java).setAction(a)
            e.forEach { i.putExtra(it.first, it.second) }
            // اگر اندروید شروع سرویس را از پس‌زمینه رد کرد، برنامه نبندد
            runCatching { if (a == START && Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }
        }
    }

    private class Sp(val dec: OpusDecoder, val tr: AudioTrack) {
        @Volatile var last = 0L
        // بافرهای قابل استفاده‌ی مجدد: دیگر برای هر بسته‌ی صدا آرایه‌ی تازه ساخته نمی‌شود (فشار کمتر به GC، لگ کمتر داخل بازی)
        val pcm = ShortArray(FRAME * 3)
        val out = ShortArray(FRAME * 3 * 2)
        val ext = ShortArray(ITD_MAX + FRAME * 3)
        // وضعیت پخش سه‌بعدی برای هر گوینده (نرم‌کردن بین بسته‌ها تا صدا «تق‌تق» نکند)
        var init = false; var pan = 0f; var fb = 0f; var gain = 0f
        var lpL = 0f; var lpR = 0f
        val hist = ShortArray(ITD_MAX)
        val c0 = FloatArray(4); val c1 = FloatArray(4)
    }

    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val h = Handler(Looper.getMainLooper())
    private val sps = ConcurrentHashMap<String, Sp>()
    private val prefs by lazy { getSharedPreferences("v", 0) }
    private lateinit var am: AudioManager
    private var ws: WebSocket? = null
    private var overlay: Overlay? = null
    private var capThread: Thread? = null
    private var url = ""
    /** آدرس سرور ویس داخل خود برنامه ثابت است (BuildConfig.DEFAULT_SERVER)؛ کاربر چیزی تایپ نمی‌کند */
    private val serverUrl: String get() = BuildConfig.DEFAULT_SERVER.trim()
    private var tries = 0
    private var groupPass = ""
    private val verCode by lazy { PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0)).toInt() }
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var connected = false
    // UDP
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var lastPong = 0L
    private var sid = ByteArray(0)
    private var key: SecretKeySpec? = null
    private val txn = AtomicInteger()

    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); am = getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // ---------- ماندن در پس‌زمینه: قفل CPU و Wi‑Fi، و وصل شدن فوری بعد از عوض‌شدن شبکه ----------
    private var wl: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private fun acquireLocks() {
        try {
            if (wl == null) wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minevoice:svc").apply { setReferenceCounted(false) }
            if (wl?.isHeld != true) wl?.acquire()
        } catch (e: Exception) { }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "minevoice:wifi").apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (e: Exception) { }
    }

    private fun releaseLocks() {
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) { }
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) { }
    }

    private fun watchNetwork() {
        if (netCb != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // شبکه‌ی جدید آمد و اتصال نداریم: بدون صبر برای مهلت تلاش دوباره، وصل شو
            override fun onAvailable(n: Network) { h.post { if (running && !stopping && ws == null) { tries = 0; connect(null) } } }
            // شبکه‌ی فعلی رفت (مثلاً Wi‑Fi به دیتا): سوکت قدیمی مرده است، قطعش کن تا دوباره وصل شود
            override fun onLost(n: Network) { h.post { if (running && !stopping && connected) ws?.cancel() } }
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb) else cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCb = cb
        } catch (e: Exception) { }
    }

    private fun unwatchNetwork() {
        netCb?.let { try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (e: Exception) { } }
        netCb = null
    }

    /** شروع حالت اجرا؛ هم برای شروع عادی و هم وقتی سیستم سرویس بسته‌شده را برمی‌گرداند */
    private fun beginRunning() {
        running = true; stopping = false
        val base = load(this)
        ui.update { base }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = base.speaker
        if (base.overlay) showOverlay()
        acquireLocks(); watchNetwork()
        h.postDelayed(notifRun, 2000)
    }

    /** بعد از چند بار تلاش ناموفق: اعلان «سرور ویس در دسترس نیست» (با وصل‌شدن پاک می‌شود) */
    private fun notifyDown() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("v2", "هشدار ویس", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(4, NotificationCompat.Builder(this, "v2").setSmallIcon(R.drawable.ic_stat_mic_off)
                .setContentTitle("سرور ویس در دسترس نیست").setContentText("اینترنت یا سرور قطع است؛ تلاش برای وصل‌شدن ادامه دارد.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (e: Exception) { }
    }

    /** اگر سیستم برنامه را بست و اجازه‌ی شروع دوباره نداد، حداقل یک اعلان بدهیم که با یک ضربه وصل شود */
    private fun notifyKilled() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("v2", "هشدار ویس", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(2, NotificationCompat.Builder(this, "v2").setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("ویس قطع شد").setContentText("سیستم برنامه را بست. برای وصل‌شدن دوباره ضربه بزن.")
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }
    // به‌روزرسانی‌های پشت‌سرهم یکی می‌شوند: شناور و اعلان حداکثر هر ~۱۲۰ میلی‌ثانیه یک‌بار دوباره ساخته می‌شوند
    private val uiPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun upd(f: (Ui) -> Ui) {
        ui.update(f)
        if (uiPending.compareAndSet(false, true)) h.postDelayed({ uiPending.set(false); overlay?.render(ui.value); updateNotif() }, 120)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) { // سیستم سرویس را بعد از بسته‌شدن دوباره ساخته (فقط اگر قبلاً در حال اجرا بودیم و کاربر قطع نکرده)
            if (running) return START_STICKY
            if (!prefs.getBoolean("want", false) || prefs.getString("token", null) == null) { stopSelf(); return START_NOT_STICKY }
            if (!enterForeground()) { notifyKilled(); stopSelf(); return START_NOT_STICKY }
            beginRunning()
            url = serverUrl
            tries = 0; connect(null)
            return START_STICKY
        }
        val a = i.action

        // تنظیمات و دکمه‌ی شناور حتی وقتی وصل نیستیم هم ذخیره می‌شوند
        if (a == SET) {
            set(i.getStringExtra("k") ?: "", i.getStringExtra("v") ?: "")
            if (!running) stopSelf()
            return if (running) START_STICKY else START_NOT_STICKY
        }
        if (a == OVERLAY) {
            val on = i.getStringExtra("on") == "1"
            prefs.edit().putBoolean("overlay", on).apply()
            upd { it.copy(overlay = on) }
            if (running) { if (on) showOverlay() else hideOverlay() } else stopSelf()
            return if (running) START_STICKY else START_NOT_STICKY
        }

        if (a == START) {
            if (!running) {
                if (!enterForeground()) {
                    ui.update { it.copy(state = St.Idle, msg = "سرویس ویس شروع نشد.") }
                    stopSelf(); return START_NOT_STICKY
                }
                beginRunning()
            }
            prefs.edit().putBoolean("want", true).apply() // تا اگر سیستم بستش، خودش دوباره بیاورد؛ با «قطع» پاک می‌شود
            url = serverUrl
            prefs.edit().remove("url").apply() // آدرس قدیمیِ ذخیره‌شده (مثلاً IP) نباید دوباره استفاده شود
            if (url.isBlank()) { shutdown(St.Idle, "آدرس سرور داخل برنامه تنظیم نشده است."); return START_NOT_STICKY }
            val code = i.getStringExtra("code")?.ifBlank { null }
            if (code == null && prefs.getString("token", null) == null)
                shutdown(St.NeedsLink, "اول کد را از /voice link بگیرید.")
            else { ws?.cancel(); tries = 0; connect(code) }
            return START_STICKY
        }

        if (!running) { stopSelf(); return START_NOT_STICKY }
        when (a) {
            STOP -> shutdown(St.Idle, "")
            MUTE -> toggleMute()
            DEAF -> toggleDeaf()
            TOP -> if (connected) ws?.send(JSONObject().put("t", "top").toString())
            RECONNECT -> { ws?.cancel(); tries = 0; connect(null) }
            INVREPLY -> {
                val ok = i.getBooleanExtra("ok", false); val gid = i.getStringExtra("id") ?: ""
                if (connected) ws?.send(JSONObject().put("t", "invreply").put("ok", ok).put("id", gid).toString())
                nmgr().cancel(3)
            }
            SPK -> {
                val on = !ui.value.speaker; am.isSpeakerphoneOn = on
                prefs.edit().putBoolean("spk", on).apply(); upd { it.copy(speaker = on) }
            }
            PTT -> upd { it.copy(pttDown = i.getStringExtra("d") == "1") }
            GROUP -> {
                // منتظر تأیید سرور می‌مانیم (رمز غلط / گروه بسته → سرور رد می‌کند)
                val id = i.getStringExtra("id") ?: ""
                groupPass = i.getStringExtra("pass") ?: ""
                if (connected) ws?.send(JSONObject().put("t", "group").put("id", if (id.isEmpty()) JSONObject.NULL else id).put("pass", groupPass).toString())
            }
            ADM -> if (connected) {
                val j = JSONObject().put("t", "adm")
                for (k in listOf("op", "name", "id", "pass")) i.getStringExtra(k)?.let { j.put(k, it) }
                i.getStringExtra("min")?.toIntOrNull()?.let { j.put("minutes", it) }
                ws?.send(j.toString())
            }
        }
        return START_STICKY
    }

    private fun set(k: String, v: String) {
        val e = prefs.edit()
        when (k) {
            "ptt" -> { e.putBoolean("ptt", v == "1"); upd { it.copy(ptt = v == "1", pttDown = false) } }
            "mic" -> v.toFloatOrNull()?.let { f -> e.putFloat("mic", f); upd { it.copy(micGain = f) } }
            "out" -> v.toFloatOrNull()?.let { f -> e.putFloat("out", f); upd { it.copy(outGain = f) }; if (connected) ws?.send(JSONObject().put("t", "out").put("v", f.toDouble()).toString()) }
            "vad" -> v.toFloatOrNull()?.let { f -> e.putFloat("vad", f.coerceIn(0f, 1f)); upd { it.copy(vad = f.coerceIn(0f, 1f)) } }
            "vol" -> {
                val parts = v.split('\t')
                val f = parts.getOrNull(1)?.toFloatOrNull()
                if (parts.size == 2 && parts[0].isNotEmpty() && f != null) {
                    val key = parts[0].lowercase()
                    val m = ui.value.vols.toMutableMap(); if (f in 0.97f..1.03f) m.remove(key) else m[key] = f
                    if (connected) ws?.send(JSONObject().put("t", "vol").put("n", parts[0]).put("v", f.toDouble()).toString())
                    val o = JSONObject(); m.forEach { (k, x) -> o.put(k, x.toDouble()) }
                    e.putString("vols", o.toString()); upd { it.copy(vols = m) }
                }
            }
            "stereo" -> { e.putBoolean("stereo", v == "1"); upd { it.copy(stereo = v == "1") } }
            "compact" -> { e.putBoolean("compact", v == "1"); upd { it.copy(compact = v == "1") } }
            "ovTeam" -> { e.putBoolean("ovTeam", v == "1"); upd { it.copy(ovTeam = v == "1") } }
            "ovOthers" -> { e.putBoolean("ovOthers", v == "1"); upd { it.copy(ovOthers = v == "1") } }
            "scale" -> v.toFloatOrNull()?.let { f ->
                e.putFloat("scale", f); upd { it.copy(scale = f) }
                if (overlay?.shown == true) { hideOverlay(); showOverlay() }
            }
        }
        e.apply()
    }

    /** اسم کسی که برای اولین بار صدایش را می‌شنویم؛ برای لیست «صدای هر بازیکن» (حداکثر ۳۰ اسم) */
    private fun rememberSeen(name: String) {
        val l = (ui.value.seen + name).takeLast(30)
        prefs.edit().putString("seen", JSONArray(l).toString()).apply()
        upd { it.copy(seen = l) }
    }

    /** تنظیمات صدای محلی را به سرور می‌دهد (برای ادغام) و نسخه‌ی سرور را می‌گیرد */
    private fun sendPrefs() {
        if (!connected) return
        val vj = JSONObject(); ui.value.vols.forEach { (k, x) -> vj.put(k, x.toDouble()) }
        ws?.send(JSONObject().put("t", "prefs").put("out", ui.value.outGain.toDouble()).put("vols", vj).toString())
    }

    /** «غیرفعال‌کردن همه‌ی صدا»: نه می‌شنوی نه شنیده می‌شوی */
    private fun toggleDeaf() { upd { it.copy(deaf = !it.deaf) }; sendState(); h.post { updateNotif(true) } }

    private fun toggleMute() { upd { it.copy(muted = !it.muted) }; sendState(); h.post { updateNotif(true) } }
    /** وضعیت میکروفون را به سرور خبر می‌دهیم تا بالای سرت آیکون بلندگوی خاموش بیاید */
    private fun sendState() { if (connected) ws?.send(JSONObject().put("t", "st").put("m", ui.value.muted).put("d", ui.value.deaf).toString()) }

    private fun showOverlay() {
        if (overlay == null) overlay = Overlay(this, { toggleMute() },
            { d -> upd { it.copy(pttDown = d) } },
            { x, y -> prefs.edit().putInt("ox", x).putInt("oy", y).apply() },
            { toggleDeaf() })
        overlay?.show(prefs.getInt("ox", 24), prefs.getInt("oy", 320), ui.value)
    }
    private fun hideOverlay() { overlay?.hide(); overlay = null }

    // ---------- شبکه ----------
    /** آدرس بدون http/https: اگر IP یا پورت داشته باشد ws:// (بدون TLS)، وگرنه wss:// */
    private fun wsUrl(s: String): String {
        val t = s.trim().trimEnd('/').removeSuffix("/ws")
        return when {
            t.startsWith("ws://") || t.startsWith("wss://") -> "$t/ws"
            t.startsWith("http://") -> "ws://" + t.removePrefix("http://") + "/ws"
            t.startsWith("https://") -> "wss://" + t.removePrefix("https://") + "/ws"
            t.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(t) -> "ws://$t/ws"
            else -> "wss://$t/ws"
        }
    }
    private fun hostOf(s: String) =
        runCatching { URI(if ("://" in s) s.trim() else "http://${s.trim()}").host }.getOrNull()

    private fun connect(code: String?) {
        upd { it.copy(state = if (tries == 0) St.Connecting else St.Reconnecting) }
        val req = try { Request.Builder().url(wsUrl(url)).build() } catch (e: Exception) {
            shutdown(St.Idle, "آدرس سرور نامعتبر است."); return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                if (w !== ws) return
                w.send((if (code != null) JSONObject().put("t", "login").put("code", code).put("v", verCode)
                else JSONObject().put("t", "resume").put("token", prefs.getString("token", "")).put("v", verCode)).toString())
            }
            override fun onMessage(w: WebSocket, text: String) {
                if (w !== ws) return
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                when (j.optString("t")) {
                    "ok" -> {
                        prefs.edit().putString("token", j.getString("token")).apply()
                        tries = 0; connected = true
                        nmgr().cancel(4)
                        val uj = j.optJSONObject("udp")
                        h.post {
                            upd { it.copy(state = St.Connected, name = j.optString("name"), msg = "",
                                staffMuted = j.optBoolean("muted"), udp = false, inGame = j.optBoolean("game"),
                                admin = j.optBoolean("admin"), ping = -1, avatar = j.optString("avatar"),
                                connSince = if (it.connSince > 0) it.connSince else System.currentTimeMillis(),
                                update = j.optJSONObject("update")?.toUpdate()) }
                            startAudio(); sendGroup(); startPing(); sendState(); sendPrefs()
                            if (uj != null) hostOf(url)?.let { openUdp(it, uj.getInt("port"), uj.getString("sid"), uj.getString("key")) }
                        }
                    }
                    "update" -> {
                        // آپدیت اجباری: اتصال بسته می‌شود و صفحه‌ی آپدیت (با متن تو) نشان داده می‌شود
                        val info = j.toUpdate()
                        h.post { shutdown(St.Idle, ""); ui.update { it.copy(update = info) } }
                    }
                    "prefs" -> {
                        // تنظیمات صدا از سرور (از منوی داخل بازی یا از گوشی دیگر) → روی همین گوشی هم اعمال و ذخیره شود
                        val o = j.optDouble("out", 1.0).toFloat()
                        val m = HashMap<String, Float>()
                        j.optJSONObject("vols")?.let { vj -> for (k in vj.keys()) m[k.lowercase()] = vj.optDouble(k, 1.0).toFloat() }
                        val oj = JSONObject(); m.forEach { (k, x) -> oj.put(k, x.toDouble()) }
                        prefs.edit().putFloat("out", o).putString("vols", oj.toString()).apply()
                        upd { it.copy(outGain = o, vols = m) }
                    }
                    "pong" -> { val ts = j.optLong("ts"); if (ts > 0) setPing((SystemClock.elapsedRealtime() - ts).toInt()) }
                    "staff" -> upd { it.copy(staffMuted = j.optBoolean("muted")) }
                    "game" -> upd { it.copy(inGame = j.optBoolean("v")) }
                    "role" -> upd { it.copy(admin = j.optBoolean("admin"), adm = if (j.optBoolean("admin")) it.adm else Adm()) }
                    "notice" -> upd { it.copy(notice = j.optString("m"), noticeId = it.noticeId + 1) }
                    "group" -> {
                        val gid = if (j.isNull("id")) "" else j.optString("id")
                        upd { it.copy(group = gid,
                            members = if (gid.isEmpty() || gid != it.group) emptyList() else it.members,
                            membersOff = if (gid.isEmpty() || gid != it.group) emptyList() else it.membersOff,
                            groupSince = if (gid.isEmpty()) 0L else if (gid != it.group || it.groupSince == 0L) System.currentTimeMillis() else it.groupSince) }
                        nmgr().cancel(3)
                    }
                    "top" -> {
                        val l = ArrayList<TopEntry>()
                        j.optJSONArray("list")?.let { a -> for (x in 0 until a.length()) { val o = a.getJSONObject(x); l.add(TopEntry(o.optString("n"), o.optLong("ms"))) } }
                        val me = j.optJSONObject("me")
                        upd { it.copy(top = l, myTalkMs = me?.optLong("ms") ?: 0L, myRank = me?.optInt("rank") ?: 0) }
                    }
                    "gm" -> upd { it.copy(members = j.strs("m"), membersOff = j.strs("o")) }
                    "invite" -> showInviteNotif(j.optString("from"), j.optString("id"))
                    "groups" -> {
                        val a = j.optJSONArray("list")
                        val l = ArrayList<GroupInfo>()
                        if (a != null) for (x in 0 until a.length()) {
                            val o = a.getJSONObject(x)
                            l.add(GroupInfo(o.getString("id"), o.optInt("n"), o.optBoolean("l"), o.optBoolean("x")))
                        }
                        upd { it.copy(groups = l, openGroups = j.optBoolean("open")) }
                    }
                    "adm" -> {
                        val us = ArrayList<AdmUser>(); val gs = ArrayList<AdmGroup>()
                        j.optJSONArray("users")?.let { a -> for (x in 0 until a.length()) { val o = a.getJSONObject(x)
                            us.add(AdmUser(o.getString("n"), o.optString("g"), o.optBoolean("ig"), o.optBoolean("m"), o.optBoolean("a"), o.optBoolean("u"))) } }
                        j.optJSONArray("groups")?.let { a -> for (x in 0 until a.length()) { val o = a.getJSONObject(x)
                            gs.add(AdmGroup(o.getString("id"), o.strs("m"), o.optBoolean("x"), o.optBoolean("l"))) } }
                        val logs = ArrayList<LogEntry>()
                        j.optJSONArray("log")?.let { a -> for (x in 0 until a.length()) { val o = a.getJSONObject(x); logs.add(LogEntry(o.optLong("t"), o.optString("s"))) } }
                        upd { it.copy(adm = Adm(us, gs, j.strs("bans"), logs)) }
                    }
                    "err" -> {
                        // هر خطای سرور پایانی است؛ خطای «bad» یعنی کد/توکن نامعتبر (باید دوباره لینک بگیرد)
                        val bad = j.optBoolean("bad")
                        if (bad) prefs.edit().remove("token").apply()
                        h.post { shutdown(if (bad) St.NeedsLink else St.Idle, j.optString("m")) }
                    }
                }
            }
            override fun onMessage(w: WebSocket, bytes: ByteString) { if (w === ws) play(bytes.toByteArray()) }
            override fun onClosed(w: WebSocket, code: Int, reason: String) = drop(w)
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = drop(w)
        })
    }

    private fun drop(w: WebSocket) {
        if (w !== ws) return
        ws = null; connected = false; closeUdp()
        if (!stopping) h.post { retry() }
    }

    private fun retry() {
        if (stopping || !running) return
        tries++
        if (tries == 3) notifyDown()
        upd { it.copy(state = St.Reconnecting, msg = "تلاش دوباره ($tries)", udp = false, inGame = false, ping = -1) }
        h.postDelayed({ if (!stopping && running && ws == null) connect(null) }, minOf(15000L, 1000L shl minOf(tries, 4)))
    }

    private fun sendGroup() {
        val g = ui.value.group
        if (connected && g.isNotEmpty()) ws?.send(JSONObject().put("t", "group").put("id", g).put("pass", groupPass).toString())
    }

    // ---------- کیفیت اتصال: ping روی UDP (در همان بسته‌ی keep-alive) یا روی TCP ----------
    private val pingRun = object : Runnable {
        override fun run() {
            if (!running || !connected) return
            if (!udpOk()) ws?.send(JSONObject().put("t", "ping").put("ts", SystemClock.elapsedRealtime()).toString())
            h.postDelayed(this, 3000)
        }
    }
    private fun startPing() { h.removeCallbacks(pingRun); h.postDelayed(pingRun, 1000) }
    private fun setPing(rtt: Int) {
        val r = rtt.coerceIn(1, 5000); val old = ui.value.ping
        val v = if (old < 0) r else (old * 0.6f + r * 0.4f).toInt()
        if (v != old) upd { it.copy(ping = v) }
    }

    // ---------- UDP رمزشده؛ اگر جواب نیامد (بسته بودن UDP) خودکار روی TCP می‌رود ----------
    private fun udpOk() = SystemClock.elapsedRealtime() - lastPong < 6000

    private fun openUdp(host: String, port: Int, sidHex: String, keyHex: String) {
        closeUdp()
        sid = sidHex.hex(); key = SecretKeySpec(keyHex.hex(), "AES")
        Thread {
            try {
                val s = DatagramSocket(); s.soTimeout = 1000
                s.connect(InetAddress.getByName(host), port); sock = s
                val buf = ByteArray(1500); var lastPing = 0L
                while (sock === s && running) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPing > 2000) { lastPing = now; udpSend(byteArrayOf(2) + ByteBuffer.allocate(8).putLong(SystemClock.elapsedRealtime()).array()) }
                    try { val p = DatagramPacket(buf, buf.size); s.receive(p); udpRecv(buf, p.length) }
                    catch (e: SocketTimeoutException) { }
                }
            } catch (e: Exception) { }
        }.start()
    }

    private fun closeUdp() { val s = sock; sock = null; lastPong = 0; s?.close() }

    private fun udpSend(plain: ByteArray) {
        val s = sock ?: return; val k = key ?: return
        try {
            val n = txn.incrementAndGet(); val nonce = ByteArray(12)
            for (b in 0..3) nonce[8 + b] = (n ushr (24 - 8 * b)).toByte()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
            val out = sid + nonce + c.doFinal(plain)
            s.send(DatagramPacket(out, out.size))
        } catch (e: Exception) { }
    }

    private fun udpRecv(b: ByteArray, len: Int) {
        val k = key ?: return
        if (len < 37) return
        val p = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, b.copyOfRange(8, 20)))
            c.doFinal(b, 20, len - 20)
        } catch (e: Exception) { return }
        if (p.isEmpty()) return
        when (p[0].toInt()) {
            2 -> {
                val now = SystemClock.elapsedRealtime(); lastPong = now
                if (p.size >= 9) setPing((now - ByteBuffer.wrap(p, 1, 8).long).toInt())
            }
            1 -> play(p.copyOfRange(1, p.size))
        }
    }

    // ---------- ضبط و ارسال ----------
    private fun startAudio() {
        if (capThread?.isAlive == true) return
        capThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8))
                rec = r
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    upd { it.copy(msg = "میکروفون باز نشد. اجازه‌ی میکروفون را بررسی کنید.") }
                    return@Thread
                }
                try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true } catch (e: Exception) { }
                try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true } catch (e: Exception) { }
                val enc = OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
                enc.bitrate = 24000
                enc.complexity = 4 // پیش‌فرض ۱۰ است و روی گوشی ضعیف CPU زیادی می‌خورد؛ برای صحبت ۴ کافی است
                val pcm = ShortArray(FRAME); val out = ByteArray(400)
                var talk = 0L; var lastUi = 0L
                r.startRecording()
                while (running && !stopping) {
                    var n = 0
                    while (n < FRAME) { val got = r.read(pcm, n, FRAME - n); if (got <= 0) break; n += got }
                    if (n < FRAME) { Thread.sleep(10); continue }
                    val u = ui.value; val mg = u.micGain
                    if (mg != 1f) for (k in pcm.indices) pcm[k] = (pcm[k] * mg).toInt().coerceIn(-32768, 32767).toShort()
                    var e = 0.0; for (s in pcm) e += s.toDouble() * s
                    val now = SystemClock.elapsedRealtime()
                    val rms = sqrt(e / FRAME)
                    if (rms > vadThreshold(u.vad)) talk = now
                    val lvl = (ln(1.0 + rms) / ln(4001.0)).toFloat().coerceIn(0f, 1f)
                    val open = if (u.ptt) u.pttDown else now - talk < 400
                    val active = u.inGame || u.group.isNotEmpty() // فعال: داخل سرور ماینکرفت یا داخل گروه
                    val sending = connected && active && !u.muted && !u.deaf && !u.staffMuted && open
                    if (sending) {
                        val len = enc.encode(pcm, 0, FRAME, out, 0, out.size)
                        if (udpOk()) udpSend(byteArrayOf(1) + out.copyOf(len)) else ws?.send(out.toByteString(0, len))
                    }
                    if (now - lastUi > 200) {
                        lastUi = now
                        val sp = sps.filter { now - it.value.last < 600 }.keys.toList()
                        val ok = udpOk()
                        val cur = ui.value
                        if (cur.speaking != sp || cur.talking != sending || cur.udp != ok || abs(cur.level - lvl) > 0.05f)
                            upd { it.copy(speaking = sp, talking = sending, udp = ok, level = lvl) }
                    }
                }
            } catch (e: Throwable) {
                upd { it.copy(msg = "خطا در ضبط صدا") }
            } finally {
                try { rec?.stop() } catch (e: Exception) { }
                try { rec?.release() } catch (e: Exception) { }
            }
        }.also { it.start() }
    }

    // ---------- دریافت و پخش ----------
    private var lastSweep = 0L

    private fun newSp(): Sp {
        val buf = maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), RATE * 2)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build()
        tr.write(ShortArray(FRAME * 3 * 2), 0, FRAME * 3 * 2)
        tr.play()
        return Sp(OpusDecoder(RATE, 1), tr)
    }
    private fun free(s: Sp) { try { s.tr.stop() } catch (e: Exception) { }; try { s.tr.release() } catch (e: Exception) { } }

    /**
     * [گین][pan][جلو/عقب][طول نام][نام][Opus]
     * pan: ۰=کاملاً چپ، ۱۲۸=وسط، ۲۵۵=کاملاً راست. جلو/عقب: ۰=پشت سر، ۱۲۸=کنار، ۲۵۵=جلو.
     * سرور از روی جهت نگاه تو (yaw) و جای گوینده حسابشان می‌کند. اینجا برای هدفون سه کار انجام می‌شود:
     * ۱) تفاوت بلندی دو گوش، ۲) تأخیر کوچک گوش دورتر (ITD)، ۳) نرم‌شدن (فیلتر بالا) گوش دورتر و صدای پشت سر.
     */
    private fun play(a: ByteArray) {
        if (a.size < 5) return
        val u = ui.value
        if (u.deaf) return
        val panT = if (u.stereo) ((a[1].toInt() and 255) - 128) / 127f else 0f
        val fbT = if (u.stereo) ((a[2].toInt() and 255) - 128) / 127f else 0f
        val n = a[3].toInt() and 255; val off = 4 + n
        if (a.size <= off) return
        val name = String(a, 4, n, Charsets.UTF_8)
        val gT = (a[0].toInt() and 255) / 255f * u.outGain * (u.vols[name.lowercase()] ?: 1f)
        if (name !in u.seen) rememberSeen(name)
        val now = SystemClock.elapsedRealtime()
        val sp = sps[name] ?: try { newSp().also { sps[name] = it } } catch (e: Exception) { return }
        synchronized(sp) {
            try {
                val pcm = sp.pcm; val out = sp.out; val ext = sp.ext
                val len = sp.dec.decode(a, off, a.size - off, pcm, 0, pcm.size, false)
                if (!sp.init) { sp.init = true; sp.pan = panT; sp.fb = fbT; sp.gain = gT }
                System.arraycopy(sp.hist, 0, ext, 0, ITD_MAX)
                System.arraycopy(pcm, 0, ext, ITD_MAX, len)
                val p0 = sp.pan; val f0 = sp.fb; val g0 = sp.gain
                // ضرایب فقط دو بار برای هر بسته حساب می‌شوند (اول و آخر) و بین‌شان درون‌یابی می‌شود؛
                // قبلاً برای هر نمونه exp و cos و sin صدا زده می‌شد که روی گوشی‌های ضعیف بار CPU زیادی داشت
                fun coefs(p: Float, f: Float, g: Float, o: FloatArray) {
                    val behind = if (f < 0f) -f else 0f
                    val th = (p.coerceIn(-1f, 1f) + 1f) * (Math.PI.toFloat() / 4f)
                    val damp = g * (1f - 0.15f * behind)
                    o[0] = min(1f, cos(th) * 1.4142135f) * damp
                    o[1] = min(1f, sin(th) * 1.4142135f) * damp
                    val fcL = (7500f - 5000f * max(0f, p)) * (1f - 0.35f * behind)
                    val fcR = (7500f - 5000f * max(0f, -p)) * (1f - 0.35f * behind)
                    o[2] = 1f - exp(-6.2831855f * fcL / RATE)
                    o[3] = 1f - exp(-6.2831855f * fcR / RATE)
                }
                val c0 = sp.c0; val c1 = sp.c1
                coefs(p0, f0, g0, c0); coefs(panT, fbT, gT, c1)
                var lpL = sp.lpL; var lpR = sp.lpR
                val inv = 1f / len
                for (i in 0 until len) {
                    val k = (i + 1) * inv
                    val p = p0 + (panT - p0) * k
                    val d = (abs(p) * ITD_MAX + 0.5f).toInt()
                    // گوینده سمت راست → گوش چپ دورتر است و کمی دیرتر می‌شنود (و برعکس)
                    val sl = if (p > 0f) ext[ITD_MAX + i - d] else ext[ITD_MAX + i]
                    val sr = if (p < 0f) ext[ITD_MAX + i - d] else ext[ITD_MAX + i]
                    val gl = c0[0] + (c1[0] - c0[0]) * k
                    val gr = c0[1] + (c1[1] - c0[1]) * k
                    val al = c0[2] + (c1[2] - c0[2]) * k
                    val ar = c0[3] + (c1[3] - c0[3]) * k
                    lpL += al * (sl * gl - lpL)
                    lpR += ar * (sr * gr - lpR)
                    out[2 * i] = lpL.toInt().coerceIn(-32768, 32767).toShort()
                    out[2 * i + 1] = lpR.toInt().coerceIn(-32768, 32767).toShort()
                }
                sp.pan = panT; sp.fb = fbT; sp.gain = gT; sp.lpL = lpL; sp.lpR = lpR
                System.arraycopy(ext, ext.size - ITD_MAX, sp.hist, 0, ITD_MAX)
                sp.tr.write(out, 0, len * 2, AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: Exception) { }
            sp.last = now
        }
        // ترک‌های بیکار را دیرتر ببند (۳۰ ثانیه)، تا ساختن دوباره‌ی AudioTrack وسط بازی هر چند ثانیه یک‌بار پرش ایجاد نکند
        if (now - lastSweep > 3000) {
            lastSweep = now
            sps.entries.removeAll { if (now - it.value.last > 30000) { free(it.value); true } else false }
        }
    }

    // ---------- پایان ----------
    private fun cleanup() {
        h.removeCallbacksAndMessages(null)
        ws?.cancel(); ws = null; closeUdp()
        hideOverlay()
        sps.values.forEach { free(it) }; sps.clear()
        unwatchNetwork(); releaseLocks()
        try { am.mode = AudioManager.MODE_NORMAL } catch (e: Exception) { }
    }

    private fun shutdown(s: St, msg: String) {
        stopping = true; running = false; connected = false
        prefs.edit().putBoolean("want", false).apply()
        nmgr().cancel(3); nmgr().cancel(4)
        cleanup()
        ui.update { load(this).copy(state = s, msg = msg) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** بعد از چرخش صفحه، دکمه‌ی شناور از لبه‌ی صفحه بیرون نماند */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.clamp()
    }

    override fun onDestroy() {
        running = false; stopping = true; connected = false
        cleanup()
        super.onDestroy()
    }


    // ---------- اعلان: وضعیت زنده، اعضای گروه، زمان، دکمه‌ها ----------
    private var lastSig = ""
    private var lastNotifAt = 0L
    private val notifRun = object : Runnable {
        override fun run() { if (!running) return; updateNotif(); h.postDelayed(this, 2000) }
    }

    private fun nmgr() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** آیکون بزرگ گرد با گرادیان آبی آسمانی و یک حرف یا عدد وسطش؛ وقتی سر بازیکن نیست */
    private val badgeCache = HashMap<String, Bitmap>()
    private fun badge(label: String, color: Int): Bitmap = badgeCache.getOrPut("$label#$color") {
        val sz = 128
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, sz.toFloat(), sz.toFloat(), 0xFF8BD8FF.toInt(), color, android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(sz / 2f, sz / 2f, sz / 2f, p)
        p.shader = null; p.color = 0xFFFFFFFF.toInt(); p.textAlign = android.graphics.Paint.Align.CENTER
        p.textSize = sz * 0.5f; p.isFakeBoldText = true
        cv.drawText(label, sz / 2f, sz / 2f - (p.descent() + p.ascent()) / 2f, p)
        bmp
    }

    private fun act(code: Int, action: String): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, VoiceService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotif(u: Ui): Pair<String, Notification> {
        val live = u.state == St.Connected
        val inGroup = u.group.isNotEmpty()
        val flag = when { u.deaf -> "🎧 صدا بسته"; u.muted || u.staffMuted -> "🔇 میوت"; else -> "🎤 روشن" }
        val title = when { live -> "رویال ویس · $flag"; u.state == St.Reconnecting -> "در حال وصل‌شدن مجدد…"; else -> "در حال اتصال…" }
        val lines = ArrayList<String>()
        if (!live) lines.add("اتصال برقرار نیست؛ تلاش ادامه دارد.")
        else if (inGroup) {
            lines.add(if (u.inGame) "🟢 آنلاین داخل سرور" else "⚪ خارج از سرور")
            lines.add("👥 گروه ${u.group} · ${u.members.size} نفر")
            u.members.take(12).forEach { lines.add((if (it in u.speaking) "🔊 " else "• ") + it) }
            if (u.members.size > 12) lines.add("و ${u.members.size - 12} نفر دیگر")
        } else {
            lines.add(if (u.inGame) "🟢 آنلاین داخل سرور · تماس نزدیکی" else "⚪ خارج از سرور · تماس نزدیکی")
            if (u.speaking.isEmpty()) lines.add("الان کسی صحبت نمی‌کند")
            else { lines.add("الان صحبت می‌کنند:"); u.speaking.take(8).forEach { lines.add("🔊 $it") } }
        }
        if (live && u.ping >= 0) lines.add("اتصال: ${u.ping}ms" + (if (u.udp) " · UDP" else " · TCP"))
        val short = when {
            !live -> "اتصال برقرار نیست"
            inGroup -> "👥 ${u.group} · ${u.members.size} نفر" + (if (u.speaking.isNotEmpty()) " · 🔊 " + u.speaking.first() else "")
            u.speaking.isNotEmpty() -> "🔊 " + u.speaking.joinToString("، ")
            else -> if (u.inGame) "🟢 آنلاین داخل سرور" else "⚪ خارج از سرور"
        }
        val since = when { live && inGroup && u.groupSince > 0 -> u.groupSince; u.connSince > 0 -> u.connSince; else -> 0L }
        val sub = if (live && inGroup && u.groupSince > 0) "زمان در گروه" else "مدت تماس"
        u.members.take(12).forEach { Heads.prefetch(it, u.avatar) }
        u.speaking.take(4).forEach { Heads.prefetch(it, u.avatar) }
        val speakingNow = u.speaking.isNotEmpty()
        val tone = when {
            !live -> 0xFFF59E0B.toInt()
            u.deaf || u.muted || u.staffMuted -> 0xFFEF4466.toInt()
            else -> 0xFF1E9BFF.toInt()
        }
        val label = if (inGroup) u.members.size.toString() else u.name.take(1).uppercase().ifEmpty { "V" }
        val head = u.speaking.firstOrNull()?.let { Heads.cached(it) }
        val big = head ?: badge(label, tone)
        val b = NotificationCompat.Builder(this, "v")
            .setSmallIcon(if (u.deaf || u.muted || u.staffMuted) R.drawable.ic_stat_mic_off else R.drawable.ic_stat_mic)
            .setContentTitle(title).setContentText(short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSubText(sub)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setColor(tone).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (since > 0) b.setShowWhen(true).setWhen(since).setUsesChronometer(true) else b.setShowWhen(false)
        b.setLargeIcon(big)
        if (live) {
            b.addAction(0, if (u.muted) "باز کردن میوت" else "میوت", act(11, MUTE))
            b.addAction(0, if (u.deaf) "باز کردن صدا" else "Deafen", act(12, DEAF))
        } else if (u.state == St.Reconnecting) b.addAction(0, "وصل شو", act(13, RECONNECT))
        b.addAction(0, "قطع", act(1, STOP))
        val sig = listOf(title, short, lines.joinToString("|"), since, sub, u.muted, u.deaf, u.staffMuted, head != null, live, tone, label).joinToString("#")
        return sig to b.build()
    }

    private fun updateNotif(force: Boolean = false) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifAt < 2500) return // اعلان زیاد عوض شود SystemUI را مشغول می‌کند و بازی لگ می‌زند
        lastNotifAt = now
        try {
            val r = buildNotif(ui.value)
            if (r.first == lastSig) return
            lastSig = r.first
            nmgr().notify(1, r.second)
        } catch (e: Exception) { }
    }

    /** دعوت گروه: اعلان پرصدا با دکمه‌ی قبول و رد (اعلان اصلی همیشه بی‌صدا می‌ماند) */
    private fun showInviteNotif(from: String, id: String) {
        if (id.isEmpty()) return
        try {
            val nm = nmgr()
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("v3", "دعوت گروه", NotificationManager.IMPORTANCE_HIGH))
            fun reply(code: Int, ok: Boolean) = PendingIntent.getService(this, code,
                Intent(this, VoiceService::class.java).setAction(INVREPLY).putExtra("ok", ok).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            Heads.prefetch(from, ui.value.avatar)
            nm.notify(3, NotificationCompat.Builder(this, "v3").setSmallIcon(R.drawable.ic_stat_mic)
                .setColor(0xFF1E9BFF.toInt()).setLargeIcon(Heads.cached(from) ?: badge(from.take(1).uppercase().ifEmpty { "V" }, 0xFF1E9BFF.toInt()))
                .setContentTitle("دعوت به گروه").setContentText("$from تو را به گروه «$id» دعوت کرد")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true).setTimeoutAfter(300000L).setContentIntent(open)
                .addAction(0, "قبول", reply(31, true)).addAction(0, "رد", reply(32, false)).build())
        } catch (e: Exception) { }
    }

    private fun enterForeground(): Boolean = try { goForeground(); true } catch (e: Exception) { false }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 26) nmgr().createNotificationChannel(NotificationChannel("v", "ویس", NotificationManager.IMPORTANCE_LOW))
        val r = buildNotif(ui.value)
        lastSig = r.first
        ServiceCompat.startForeground(this, 1, r.second, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}
