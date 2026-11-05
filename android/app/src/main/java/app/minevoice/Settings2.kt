package app.minevoice

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

// ───────────────────────── تنظیمات اعلان (ذخیره در SharedPreferences) ─────────────────────────
object NotifPrefs {
    private fun p(c: Context) = c.getSharedPreferences("v", 0)
    /** کلیدها: dm، group، global (پیش‌فرض خاموش)، call، wifi (دانلود خودکار فقط با وای‌فای؛ برای مرحله‌ی رسانه) */
    fun get(c: Context, k: String, def: Boolean) = p(c).getBoolean("n_$k", def)
    fun set(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean("n_$k", v).apply()
}

/** کل زمان حضور در ویس روی این گوشی (ثانیه) برای آمار پروفایل */
object VoiceStats {
    fun secs(c: Context) = c.getSharedPreferences("v", 0).getLong("voice_secs", 0L)
    fun add(c: Context, n: Long) { val p = c.getSharedPreferences("v", 0); p.edit().putLong("voice_secs", p.getLong("voice_secs", 0L) + n).apply() }
}

// ───────────────────────── ربات (فقط مالک برنامه) ─────────────────────────
data class BotState(val minutes: Int = 10, val global: Boolean = true)
object BotX {
    val state = MutableStateFlow(BotState())
    fun onState(j: JSONObject) { state.update { BotState(j.optInt("minutes", it.minutes), j.optBoolean("global", it.global)) } }
}

// ───────────────────────── تیکت و گزارش ─────────────────────────
data class TItem(val id: String, val kind: String, val subject: String, val status: String, val ts: Long, val upd: Long, val by: String, val target: String, val n: Int, val last: String)
data class TMsg(val f: String, val n: String, val x: String, val ts: Long, val media: List<String> = emptyList())
data class TEv(val f: String, val x: String, val d: Boolean)
data class TFull(val item: TItem, val msgs: List<TMsg>, val ev: List<TEv>)
data class TixState(val staff: Boolean = false, val list: List<TItem> = emptyList(), val open: TFull? = null)

object Tix {
    val state = MutableStateFlow(TixState())
    fun setStaff(b: Boolean) = state.update { it.copy(staff = b) }
    fun closeOpen() = state.update { it.copy(open = null) }
    fun reset() { state.value = TixState() }

    private fun item(o: JSONObject) = TItem(
        o.optString("id"), o.optString("kind", "ticket"), o.optString("subject"), o.optString("status", "open"), o.optLong("ts"), o.optLong("upd"),
        o.optJSONObject("by")?.optString("n").orEmpty(), o.optString("target"), o.optInt("n"), o.optString("last")
    )
    fun onList(j: JSONObject) {
        val a = j.optJSONArray("list") ?: JSONArray()
        state.update { it.copy(staff = j.optBoolean("staff"), list = (0 until a.length()).map { i -> item(a.getJSONObject(i)) }) }
    }
    fun onGet(j: JSONObject) {
        val t = j.optJSONObject("ticket") ?: return
        val ma = t.optJSONArray("msgs") ?: JSONArray(); val ea = t.optJSONArray("ev") ?: JSONArray()
        val full = TFull(item(t),
            (0 until ma.length()).map { val o = ma.getJSONObject(it); val md = o.optJSONArray("media"); TMsg(o.optString("f"), o.optString("n"), o.optString("x"), o.optLong("ts"), if (md == null) emptyList() else (0 until md.length()).map { k -> md.getString(k) }) },
            (0 until ea.length()).map { val o = ea.getJSONObject(it); TEv(o.optString("f"), o.optString("x"), o.optInt("d") == 1) })
        state.update { it.copy(open = full) }
    }
}

private fun kindLabel(k: String) = when (k) { "report" -> "گزارش"; "appeal" -> "اعتراض"; else -> "تیکت" }
private fun statusLabel(s: String) = when (s) { "review" -> "در حال بررسی"; "closed" -> "بسته"; else -> "باز" }
private fun statusColor(s: String) = when (s) { "review" -> Sky.Warn; "closed" -> Sky.Ink2; else -> Sky.Ok }
private fun fmtTs(t: Long) = SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(t))
private fun String.lim(max: Int): String = if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

// ───────────────────────── کارت جمع‌شونده ─────────────────────────
@Composable
fun Fold(title: String, icon: ImageVector, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Glass(Modifier.fillMaxWidth(), pad = 14.dp) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = Sky.Blue, modifier = Modifier.size(22.dp))
            Text(title, color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "بستن" else "باز کردن", tint = Sky.Ink2, modifier = Modifier.size(24.dp))
        }
        if (open) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, sub: String? = null, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Sky.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Sky.Ink2, fontSize = 11.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

// ───────────────────────── بخش‌های تنظیمات ─────────────────────────
@Composable
fun PrivacySection() {
    val c = LocalContext.current
    val soc by Social.state.collectAsState()
    PrivacyBody(soc.priv) { f, v -> VoiceService.cmd(c, VoiceService.SOC, "op" to "priv", f to v) }
    Text("متن‌ها (پیوی و گروه و چت جهانی) برای جلوگیری از محتوای نامناسب با فهرست کلمات بررسی می‌شوند؛ مدیر برنامه فقط مدرک گزارش‌ها را می‌بیند.", color = Sky.Ink2, fontSize = 11.sp)
}

@Composable
fun NotifSection() {
    val c = LocalContext.current
    var dm by remember { mutableStateOf(NotifPrefs.get(c, "dm", true)) }
    var grp by remember { mutableStateOf(NotifPrefs.get(c, "group", true)) }
    var glob by remember { mutableStateOf(NotifPrefs.get(c, "global", false)) }
    var call by remember { mutableStateOf(NotifPrefs.get(c, "call", true)) }
    ToggleRow("پیام‌های خصوصی", null, dm) { dm = it; NotifPrefs.set(c, "dm", it) }
    ToggleRow("پیام‌های گروه‌ها", null, grp) { grp = it; NotifPrefs.set(c, "group", it) }
    ToggleRow("چت جهانی", "پیش‌فرض خاموش چون شلوغ است", glob) { glob = it; NotifPrefs.set(c, "global", it) }
    ToggleRow("دعوت و کارت ویس‌کال", null, call) { call = it; NotifPrefs.set(c, "call", it) }
    Text("اعلان فقط وقتی برنامه باز نیست می‌آید و متنش روی قفل‌صفحه مخفی می‌ماند. درخواست پیام غریبه‌ها و پیام ربات اعلان ندارد.", color = Sky.Ink2, fontSize = 11.sp)
}

@Composable
fun MediaSection() {
    val c = LocalContext.current
    var wifi by remember { mutableStateOf(NotifPrefs.get(c, "wifi", true)) }
    var cleared by remember { mutableStateOf(false) }
    val size = remember(cleared) { File(c.filesDir, "chats.json").takeIf { it.exists() }?.length() ?: 0L }
    ToggleRow("دانلود خودکار عکس فقط با وای‌فای", "روی اینترنت همراه با لمس دانلود می‌شود", wifi) { wifi = it; NotifPrefs.set(c, "wifi", it) }
    Text("حافظه‌ی پیام‌ها روی این گوشی: ${size / 1024} کیلوبایت", color = Sky.Ink2, fontSize = 12.sp)
    SkyButton("پاک کردن کش پیام‌ها", Icons.Rounded.Delete, Modifier.fillMaxWidth()) {
        Chats.clearAll(c); VoiceService.cmd(c, VoiceService.CHAT, "op" to "sync"); cleared = !cleared
    }
    Text("پین، پوشه‌ها و گروه‌ها می‌مانند؛ ۳۰ پیام آخر هر گفتگو دوباره از سرور می‌آید.", color = Sky.Ink2, fontSize = 11.sp)
}

@Composable
fun BlockedSection(s: Ui) {
    val c = LocalContext.current
    val soc by Social.state.collectAsState()
    if (soc.blocked.isEmpty()) Text("کسی را بلاک نکرده‌ای.", color = Sky.Ink2, fontSize = 12.sp)
    soc.blocked.forEach { p ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(p.n, 34.dp, Sky.Blue)
            Text(p.n, color = Sky.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ VoiceService.cmd(c, VoiceService.SOC, "op" to "unblk", "name" to p.n) }, enabled = s.state == St.Connected) { Text("رفع بلاک", color = Sky.Blue) }
        }
    }
}

@Composable
fun BotSection(s: Ui) {
    val c = LocalContext.current
    val b by BotX.state.collectAsState()
    LaunchedEffect(s.state) { if (s.state == St.Connected) VoiceService.cmd(c, VoiceService.BOT, "op" to "get") }
    Text("ربات هر چند دقیقه، مجموع بازیکن‌های داخل ویس‌کال‌ها را در چت جهانی و گروه‌هایی که ربات به آن‌ها اضافه شده می‌فرستد.", color = Sky.Ink2, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5 to "۵ دقیقه", 10 to "۱۰ دقیقه", 30 to "۳۰ دقیقه", 0 to "خاموش").forEach { (m, l) ->
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (b.minutes == m) Sky.Blue else Sky.Chip).clickable { VoiceService.cmd(c, VoiceService.BOT, "op" to "cfg", "minutes" to "$m") }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(l, color = if (b.minutes == m) Color.White else Sky.Ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    ToggleRow("ربات در چت جهانی", null, b.global) { VoiceService.cmd(c, VoiceService.BOT, "op" to "cfg", "global" to if (it) "1" else "0") }
    SkyButton("ارسال همین حالا", Icons.Rounded.SmartToy, Modifier.fillMaxWidth(), enabled = s.state == St.Connected) { VoiceService.cmd(c, VoiceService.BOT, "op" to "now") }
    Text("برای گروه‌ها: صفحه‌ی اطلاعات گروه ← «ربات آمار ویس‌کال».", color = Sky.Ink2, fontSize = 11.sp)
}

@Composable
fun AccountSection(s: Ui) {
    val c = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("حذف داده‌هایم", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("پروفایل، دوستی‌ها، بلاک‌ها، تنظیمات و متن همه‌ی پیام‌هایت پاک می‌شود، از گروه‌ها بیرون می‌روی و حسابت از این گوشی خارج می‌شود. برگشت ندارد. (اخطارها و بن‌ها برای جلوگیری از فرار از مجازات می‌مانند.)", color = Sky.Ink2, fontSize = 12.sp)
                Field(typed, { typed = it.take(10) }, "برای تأیید بنویس: حذف", Icons.Rounded.Delete)
            }
        },
        confirmButton = { TextButton({ if (typed.trim() == "حذف") { VoiceService.cmd(c, VoiceService.ACCT, "op" to "del"); confirm = false; typed = "" } }) { Text("حذف کن", color = Sky.Bad, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirm = false }) { Text("انصراف", color = Sky.Ink2) } })
    SkyButton("خروج از حساب", Icons.Rounded.Close, Modifier.fillMaxWidth()) { VoiceService.cmd(c, VoiceService.LOGOUT) }
    SkyButton("حذف داده‌هایم", Icons.Rounded.Delete, Modifier.fillMaxWidth(), enabled = s.state == St.Connected, danger = true) { confirm = true }
}

// ───────────────────────── صفحه‌ی گزارش و تیکت ─────────────────────────
@Composable
fun TicketsScreen(s: Ui, onBack: (() -> Unit)? = null) {
    val c = LocalContext.current
    val st by Tix.state.collectAsState()
    var newDlg by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(s.state) { if (s.state == St.Connected) VoiceService.cmd(c, VoiceService.TIX, "op" to "list") }

    if (newDlg) NewTicketDlg { newDlg = false }
    st.open?.let { t -> TicketDetail(s, st.staff, t) { Tix.closeOpen(); VoiceService.cmd(c, VoiceService.TIX, "op" to "list") }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) Icon(Icons.Rounded.ArrowBack, "بازگشت", tint = Sky.Ink, modifier = Modifier.size(28.dp).clickable { onBack() })
            Text(if (st.staff) "تیکت‌ها و گزارش‌ها (${st.list.count { it.status != "closed" }} باز)" else "تیکت‌های من", color = Sky.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        SkyButton("تیکت یا اعتراض جدید", Icons.Rounded.Add, Modifier.fillMaxWidth(), enabled = s.state == St.Connected) { newDlg = true }
        if (st.staff) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "همه", "open" to "باز", "review" to "در حال بررسی", "closed" to "بسته").forEach { (k, l0) ->
                val l = if (k.isEmpty()) l0 else "$l0 ${st.list.count { it.status == k }}"
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (filter == k) Sky.Blue else Sky.Chip).clickable { filter = k }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(l, color = if (filter == k) Color.White else Sky.Ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val shown = st.list.filter { filter.isEmpty() || it.status == filter }
        if (shown.isEmpty()) Text("موردی نیست.", color = Sky.Ink2, fontSize = 13.sp)
        shown.forEach { t ->
            Glass(Modifier.fillMaxWidth().clickable { VoiceService.cmd(c, VoiceService.TIX, "op" to "get", "id" to t.id) }, pad = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.subject, color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${kindLabel(t.kind)}${if (st.staff) " · از ${t.by}" else ""}${if (t.target.isNotEmpty()) " · علیه ${t.target}" else ""} · ${fmtTs(t.upd)}", color = Sky.Ink2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // مدیر: «منتظر پاسخ» وقتی آخرین پیام از کاربر است؛ کاربر: «پاسخ مدیریت» وقتی آخرین پیام از مدیر است
                    if (t.status != "closed" && ((st.staff && t.last == "user") || (!st.staff && t.last == "staff"))) Text(if (st.staff) "منتظر پاسخ" else "پاسخ جدید", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Sky.Warn).padding(horizontal = 8.dp, vertical = 3.dp))
                    Text(statusLabel(t.status), color = statusColor(t.status), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NewTicketDlg(onDismiss: () -> Unit) {
    val c = LocalContext.current
    var kind by remember { mutableStateOf("ticket") }
    var subject by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("تیکت جدید", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ticket" to "تیکت", "appeal" to "اعتراض به اخطار/بن").forEach { (k, l) ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (kind == k) Sky.Blue else Sky.Chip).clickable { kind = k }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(l, color = if (kind == k) Color.White else Sky.Ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Field(subject, { subject = it.lim(60) }, "عنوان", Icons.Rounded.Edit)
                OutlinedTextField(text, { text = it.lim(500) }, Modifier.fillMaxWidth(), maxLines = 5, placeholder = { Text("توضیح (حداکثر ۵۰۰ نویسه)") })
                AttachBar()
                Text("هر ساعت حداکثر ۳ تیکت. اعتراض به اخطار فقط از همین راه ممکن است.", color = Sky.Ink2, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton({ if (text.isNotBlank()) { VoiceService.cmd(c, VoiceService.TIX, "op" to "new", "kind" to kind, "subject" to subject.trim(), "x" to text.trim(), "media" to MediaX.attach.value.joinToString("\n") { it.first }); MediaX.clearAttach(); onDismiss() } }) { Text("ارسال", color = Sky.Blue, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ MediaX.clearAttach(); onDismiss() }) { Text("انصراف", color = Sky.Ink2) } })
}

@Composable
private fun TicketDetail(s: Ui, staff: Boolean, t: TFull, onBack: () -> Unit) {
    val c = LocalContext.current
    var reply by remember { mutableStateOf("") }
    var showEv by remember { mutableStateOf(false) }
    var viewId by remember { mutableStateOf<String?>(null) }
    viewId?.let { v -> ImageViewer(Msg(id = "x", mid = v)) { viewId = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ArrowBack, "بازگشت", tint = Sky.Ink, modifier = Modifier.size(28.dp).clickable { MediaX.clearAttach(); onBack() })
            Column(Modifier.weight(1f)) {
                Text(t.item.subject, color = Sky.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${kindLabel(t.item.kind)} · ${statusLabel(t.item.status)}${if (staff) " · از ${t.item.by}" else ""}${if (t.item.target.isNotEmpty()) " · علیه ${t.item.target}" else ""}", color = statusColor(t.item.status), fontSize = 12.sp)
            }
        }
        if (staff) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("open", "review", "closed").forEach { k ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (t.item.status == k) Sky.Blue else Sky.Chip).clickable { VoiceService.cmd(c, VoiceService.TIX, "op" to "status", "id" to t.item.id, "status" to k); VoiceService.cmd(c, VoiceService.TIX, "op" to "get", "id" to t.item.id) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(statusLabel(k), color = if (t.item.status == k) Color.White else Sky.Ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (staff && t.ev.isNotEmpty()) Glass(Modifier.fillMaxWidth().clickable { showEv = !showEv }, pad = 12.dp) {
            Text("مدرک: ${t.ev.size} پیام آخر گفتگو ${if (showEv) "▲" else "▼"}", color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (showEv) t.ev.forEach { e -> Text("${e.f}: ${if (e.d) "(حذف‌شده)" else e.x}", color = Sky.Ink2, fontSize = 12.sp) }
        }
        t.msgs.forEach { m ->
            val staffMsg = m.f == "staff"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (staffMsg) Arrangement.End else Arrangement.Start) {
                Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(if (staffMsg) Color(0xFFE6F6EA) else Color.White).padding(10.dp)) {
                    Text("${if (staffMsg) "مدیریت" else m.n} · ${fmtTs(m.ts)}", color = Sky.Ink2, fontSize = 10.sp)
                    Text(m.x, color = Sky.Ink, fontSize = 14.sp)
                    // مدیر: پیش‌نمایش پیکسله (لمس = واضح)؛ نویسنده‌ی تیکت: واضح
                    m.media.forEach { id -> Spacer(Modifier.height(4.dp)); PixImage(id, clear = !staff) { viewId = id } }
                }
            }
        }
        if (t.item.status != "closed" || staff) {
            OutlinedTextField(reply, { reply = it.lim(500) }, Modifier.fillMaxWidth(), maxLines = 4, placeholder = { Text("پاسخ…") })
            AttachBar()
            SkyButton("ارسال پاسخ", Icons.Rounded.Send, Modifier.fillMaxWidth(), enabled = reply.isNotBlank() && s.state == St.Connected) {
                VoiceService.cmd(c, VoiceService.TIX, "op" to "reply", "id" to t.item.id, "x" to reply.trim(), "media" to MediaX.attach.value.joinToString("\n") { it.first }); MediaX.clearAttach(); reply = ""
            }
        } else Text("این تیکت بسته شده است؛ برای ادامه تیکت تازه بفرست.", color = Sky.Ink2, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }
}
