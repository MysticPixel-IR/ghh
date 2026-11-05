package app.minevoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

// ───────────────────────── مدل و ابزار ─────────────────────────
data class MrItem(val id: String, val by: String, val chat: String, val ts: Long, val w: Int, val h: Int, val score: Double, val st: String)
data class MrState(val list: List<MrItem> = emptyList(), val global: Boolean = false, val scanner: Boolean = false)

object MediaX {
    val http = OkHttpClient()
    /** آدرس HTTP سرور؛ سرویس موقع وصل شدن پرش می‌کند */
    @Volatile var base: String = ""
    private val urls = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap()) // id ← (مسیر امضاشده، انقضا)
    private val gone = MutableStateFlow<Set<String>>(emptySet())
    val mr = MutableStateFlow(MrState())
    /** عکس‌های آپلودشده برای پیوست تیکت (شناسه، مسیر فایل محلی)؛ حداکثر ۳ */
    val attach = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    fun addAttach(id: String, path: String) = attach.update { if (it.size >= 3) it else it + (id to path) }
    fun removeAttach(id: String) = attach.update { l -> l.filterNot { it.first == id } }
    fun clearAttach() { attach.value = emptyList() }
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

    fun onUrl(j: JSONObject) {
        val id = j.optString("id")
        // کلید «#f» = لینک تصویر کامل (فقط مالک روی رسانه‌ی بررسی‌نشده و فقط وقتی بزرگ باز می‌شود)؛ سرور تصمیم می‌گیرد چه نسخه‌ای بدهد
        if (j.optBoolean("gone")) gone.update { it + id } else urls.update { it + ((id + (if (j.optBoolean("full")) "#f" else "")) to (j.optString("u") to System.currentTimeMillis() + 240_000L)) }
    }
    fun onMr(j: JSONObject) {
        val a = j.optJSONArray("list")
        val l = if (a == null) emptyList() else (0 until a.length()).map { val o = a.getJSONObject(it); MrItem(o.optString("id"), o.optString("by"), o.optString("chat"), o.optLong("ts"), o.optInt("w"), o.optInt("h"), o.optDouble("score", -1.0), o.optString("st")) }
        mr.value = MrState(l, j.optBoolean("global"), j.optBoolean("scanner"))
    }

    fun isWifi(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) { true }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1; while (o.outWidth / (s * 2) >= maxPx && o.outHeight / (s * 2) >= maxPx) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val big = maxOf(b.width, b.height)
        return if (big > maxPx) { val f = maxPx.toFloat() / big; Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) } else b
    }

    /** قبل از ارسال: چرخش EXIF اعمال، حداکثر ۱۶۰۰ پیکسل، JPEG با کیفیت ۸۰. (خروجی هیچ EXIF ندارد.) */
    fun prepare(c: Context, uri: Uri): File? = try {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.size > 40_000_000) null else {
            var bmp = decode(bytes, 1600)
            if (bmp == null) null else {
                val rot = try { when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f } } catch (e: Exception) { 0f }
                if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
                val out = File(c.cacheDir, "up_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { bmp!!.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (out.length() > 10L * 1048576) { out.delete(); null } else out
            }
        }
    } catch (e: Exception) { null }

    /** عکس پیام برای نمایش: فایل محلی (پیام در حال ارسال خودم) یا دانلود با لینک امضاشده؛ کش LRU. null = حذف شده/در دسترس نیست */
    suspend fun load(c: Context, m: Msg, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = (if (m.id.isEmpty()) m.cid else m.mid) + ":" + maxPx
        cache.get(key)?.let { return@withContext it }
        try {
            if (m.id.isEmpty()) {
                val f = File(m.vby); if (!f.exists()) return@withContext null
                return@withContext decode(f.readBytes(), maxPx)?.also { cache.put(key, it) }
            }
            if (m.mid in gone.value) return@withContext null
            val want = m.mid + (if (maxPx >= 1000) "#f" else "")
            var path = urls.value[want]?.takeIf { it.second > System.currentTimeMillis() }?.first
            if (path == null) {
                VoiceService.cmd(c, VoiceService.MEDIA, "op" to "url", "id" to m.mid, "full" to (if (maxPx >= 1000) "1" else "0"))
                var n = 0
                while (path == null && n < 40) {
                    delay(200); n++
                    if (m.mid in gone.value) return@withContext null
                    path = urls.value[want]?.takeIf { it.second > System.currentTimeMillis() }?.first
                }
            }
            if (path == null || base.isEmpty()) return@withContext null
            http.newCall(Request.Builder().url(base + path).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val b = r.body?.bytes() ?: return@withContext null
                decode(b, maxPx)?.also { cache.put(key, it) }
            }
        } catch (e: Exception) { null }
    }
}

// ───────────────────────── نمایش عکس داخل پیام ─────────────────────────
@Composable
fun MsgImage(m: Msg, onOpen: () -> Unit) {
    val c = LocalContext.current
    var bmp by remember(m.mid, m.cid) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(m.mid, m.cid) { mutableStateOf(false) }
    // «دانلود خودکار فقط با وای‌فای»: روی اینترنت همراه با لمس دانلود می‌شود
    val auto = remember { m.id.isEmpty() || !NotifPrefs.get(c, "wifi", true) || MediaX.isWifi(c) }
    var manual by remember(m.mid) { mutableStateOf(false) }
    LaunchedEffect(m.mid, m.cid, auto, manual) {
        if (bmp == null && (auto || manual)) { val b = MediaX.load(c, m, 600); if (b == null) failed = true else bmp = b }
    }
    val ratio = if (m.mw > 0 && m.mh > 0) (m.mw.toFloat() / m.mh).coerceIn(0.5f, 2f) else 1.3f
    Box(Modifier.width(240.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color(0x22000000))
        .clickable { if (bmp != null) onOpen() else if (!auto) manual = true }, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(bitmap = b.asImageBitmap(), contentDescription = "عکس", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else if (failed) Text("رسانه حذف شده یا در دسترس نیست", color = Color(0xFF6B7A90), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        else if (!auto && !manual) Text("لمس برای دانلود", color = Color(0xFF6B7A90), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        val status = when (m.ms) { "up" -> "در حال ارسال…"; "pending" -> "در حال بررسی…"; "review", "queued" -> "در صف بررسی مدیر؛ تا تأیید منتشر نمی‌شود"; else -> "" }
        if (status.isNotEmpty() && m.id.isEmpty()) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(6.dp)) {
            Text(status, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ImageViewer(m: Msg, onClose: () -> Unit) {
    val c = LocalContext.current
    var bmp by remember(m.mid, m.cid) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(m.mid, m.cid) { bmp = MediaX.load(c, m, 1600) ?: MediaX.load(c, m, 600) }
    Dialog(onDismissRequest = onClose) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black).clickable { onClose() }, contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(bitmap = b.asImageBitmap(), contentDescription = "عکس", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            else CircularProgressIndicator(Modifier.padding(40.dp), color = Color.White)
        }
    }
}

// ───────────────────────── صف بررسی رسانه (مالک و ادمین برنامه) ─────────────────────────
@Composable
fun MediaReviewSection(s: Ui) {
    val c = LocalContext.current
    val st by MediaX.mr.collectAsState()
    LaunchedEffect(s.state) { if (s.state == St.Connected) VoiceService.cmd(c, VoiceService.MR, "op" to "list") }
    Text(if (st.scanner) "اسکنر خودکار روشن است؛ فقط موارد مشکوک یا وقتی اسکنر خراب است اینجا می‌آیند."
         else "اسکنر خودکار نیست؛ هر عکس قبل از انتشار باید اینجا تأیید شود (مالک و ادمین‌ها می‌بینند).", color = Sky.Ink2, fontSize = 12.sp)
    if (s.role == "owner") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("عکس در چت جهانی", color = Sky.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("پیش‌فرض خاموش", color = Sky.Ink2, fontSize = 11.sp)
        }
        Switch(checked = st.global, onCheckedChange = { VoiceService.cmd(c, VoiceService.MR, "op" to "cfg", "global" to if (it) "1" else "0") })
    }
    if (st.list.isEmpty()) Text("صف خالی است.", color = Sky.Ink2, fontSize = 12.sp)
    st.list.forEach { ReviewRow(it) }
}

@Composable
private fun ReviewRow(item: MrItem) {
    val c = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var reveal by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) { bmp = MediaX.load(c, Msg(id = "x", mid = item.id), 600) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sky.Chip).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("از ${item.by} · ${if (item.chat.startsWith("a:")) "عکس پروفایل" else if (item.chat.startsWith("d:")) "پیوی" else if (item.chat.startsWith("c:")) "گروه" else "چت جهانی"}${if (item.score >= 0) " · امتیاز ${item.score}" else ""}${if (item.st == "queued") " · اسکنر خراب" else ""}", color = Sky.Ink2, fontSize = 11.sp)
        val b = bmp
        // پیش‌نمایش پیش‌فرض تار (پیکسله) است؛ مدیر فقط در صورت نیاز با لمس واضحش می‌کند. توجه: تار بودن فقط سمت اپ است.
        if (b != null) {
            val shown = if (reveal) b else Bitmap.createScaledBitmap(b, 14, (14f * b.height / b.width).toInt().coerceAtLeast(1), true)
            Image(bitmap = shown.asImageBitmap(), contentDescription = "پیش‌نمایش", contentScale = ContentScale.Crop, filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)).clickable { reveal = !reveal })
        } else Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkyButton("تأیید", Icons.Rounded.Check, Modifier.weight(1f)) { VoiceService.cmd(c, VoiceService.MR, "op" to "ok", "id" to item.id) }
            SkyButton("رد", Icons.Rounded.Close, Modifier.weight(1f), danger = true) { VoiceService.cmd(c, VoiceService.MR, "op" to "no", "id" to item.id) }
        }
    }
}

// ───────────────────────── پیوست تیکت ─────────────────────────
/** پیش‌نمایش عکس پیوست: برای مدیر پیش‌فرض پیکسله (لمس = واضح)؛ برای نویسنده همیشه واضح. تار بودن فقط سمت اپ است. */
@Composable
fun PixImage(mid: String, clear: Boolean, onOpen: () -> Unit) {
    val c = LocalContext.current
    var bmp by remember(mid) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(mid) { mutableStateOf(false) }
    var reveal by remember(mid) { mutableStateOf(false) }
    LaunchedEffect(mid) { val b = MediaX.load(c, Msg(id = "x", mid = mid), 600); if (b == null) failed = true else bmp = b }
    val b = bmp
    Box(Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22000000)).clickable { if (b != null) { if (clear || reveal) onOpen() else reveal = true } }, contentAlignment = Alignment.Center) {
        if (b != null) {
            val shown = if (clear || reveal) b else Bitmap.createScaledBitmap(b, 12, (12f * b.height / b.width).toInt().coerceAtLeast(1), true)
            Image(bitmap = shown.asImageBitmap(), contentDescription = "پیوست", contentScale = ContentScale.Crop, filterQuality = if (clear || reveal) FilterQuality.Medium else FilterQuality.None, modifier = Modifier.fillMaxSize())
        } else if (failed) Text("حذف‌شده", color = Color(0xFF6B7A90), fontSize = 11.sp)
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** انتخاب عکس برای پیوست (حداکثر ۳): فشرده‌سازی ← آپلود؛ پیش‌نمایش با امکان حذف */
@Composable
fun AttachBar() {
    val c = LocalContext.current
    val list by MediaX.attach.collectAsState()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) Thread {
            val f = MediaX.prepare(c, uri)
            if (f == null) Chats.say("این عکس خوانده نشد یا بزرگ‌تر از ۱۰ مگابایت است.")
            else VoiceService.cmd(c, VoiceService.MEDIA, "op" to "upload", "file" to f.absolutePath)
        }.start()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        list.forEach { (id, _) ->
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Sky.Chip).clickable { MediaX.removeAttach(id) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Image, null, tint = Sky.Blue, modifier = Modifier.size(24.dp))
                Icon(Icons.Rounded.Close, "حذف", tint = Sky.Bad, modifier = Modifier.align(Alignment.TopEnd).size(16.dp))
            }
        }
        if (list.size < 3) TextButton({ picker.launch("image/*") }) { Icon(Icons.Rounded.Image, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("افزودن عکس", color = Sky.Blue, fontSize = 12.sp) }
    }
}
