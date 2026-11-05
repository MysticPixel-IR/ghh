package app.minevoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ───────────────────────── مدل ─────────────────────────
/** st: 0 = در صف ارسال، 1 = ارسال شد، 2 = ارسال نشد. cid فقط برای پیام‌های خودم (حذف تکراری)؛ id بعد از ack سرور پر می‌شود. */
data class Msg(
    val cid: String = "", val id: String = "", val seq: Long = 0, val f: String = "", val ts: Long = 0, val x: String = "", val r: String = "member",
    val rp: String = "", val rt: String = "", val rf: String = "", val del: Boolean = false, val st: Int = 1,
    val gr: String = "", // نقش فرستنده داخل گروه (owner/admin)؛ جدا از نقش برنامه r
    val k: String = "", val vid: String = "", val vby: String = "", val vts: Long = 0L, val ve: Long = 0L, // k == "call": کارت ویس‌کال (vid اسم ویس، vby سازنده، ve انقضای دعوت)
    val mid: String = "", val mw: Int = 0, val mh: Int = 0, val ms: String = "" // k == "img": mid شناسه‌ی رسانه، ms وضعیت ارسال (up/pending/review/queued)؛ برای پیام در حال ارسال vby مسیر فایل محلی است
)

data class Chat(
    val id: String, val msgs: List<Msg> = emptyList(), val last: Long = 0, val mine: Long = 0, val peer: Long = 0,
    val typingAt: Long = 0, val typingBy: String = "", val endReached: Boolean = false,
    val req: Boolean = false // «درخواست پیام»: غریبه پیام داده و من هنوز قبول نکرده‌ام
)

data class ChatsState(
    val chats: Map<String, Chat> = emptyMap(), val pinned: Set<String> = emptySet(), val folders: Map<String, Set<String>> = emptyMap(),
    val hidden: Map<String, Long> = emptyMap(), val found: List<Prof> = emptyList(), val foundQ: String = "", val err: String = "", val errId: Int = 0
)

internal fun JSONObject.toMsg(cid: String = ""): Msg = Msg(
    cid = cid.ifEmpty { optString("cid") }, id = optString("id"), seq = optLong("seq"), f = optString("f"), ts = optLong("ts"), x = optString("x"),
    r = optString("r", "member"), rp = optString("rp"), rt = optString("rt"), rf = optString("rf"), del = optInt("d") == 1, st = optInt("st", 1), gr = optString("gr"),
    k = optString("k"), vid = optString("vid"), vby = optString("vby"), vts = optLong("vts"), ve = optLong("ve"),
    mid = optString("mid"), mw = optInt("mw"), mh = optInt("mh"), ms = optString("ms")
)

private fun Msg.toJson() = JSONObject().put("cid", cid).put("id", id).put("seq", seq).put("f", f).put("ts", ts).put("x", x).put("r", r)
    .put("rp", rp).put("rt", rt).put("rf", rf).put("d", if (del) 1 else 0).put("st", st).put("gr", gr).put("k", k).put("vid", vid).put("vby", vby).put("vts", vts).put("ve", ve).put("mid", mid).put("mw", mw).put("mh", mh).put("ms", ms)

/** ذخیره‌ی محلی (فایل JSON در حافظه‌ی برنامه) + وضعیت همه‌ی گفتگوها؛ پین، پوشه و حذف فقط روی همین گوشی‌اند. */
object Chats {
    val state = MutableStateFlow(ChatsState())
    val open = MutableStateFlow<String?>(null)
    @Volatile var me: String = ""
    /** وقتی برنامه روی صفحه است اعلان پیام نمی‌آید */
    @Volatile var appVisible: Boolean = false
    private const val KEEP = 200
    private var file: File? = null
    private val h = Handler(Looper.getMainLooper())
    private var dirty = false

    fun dmId(a: String, b: String) = "d:" + listOf(a.lowercase(), b.lowercase()).sorted().joinToString("|")
    fun peerKey(id: String): String = if (id.startsWith("d:")) id.drop(2).split("|").firstOrNull { it != me.lowercase() } ?: "" else ""

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "chats.json")
        Thread { runCatching { load() } }.start()
    }

    private fun touch() {
        if (dirty) return
        dirty = true
        h.postDelayed({ dirty = false; val snap = state.value; Thread { runCatching { save(snap) } }.start() }, 1500)
    }

    private fun upd(f: (ChatsState) -> ChatsState) { state.update(f); touch() }

    private fun save(s: ChatsState) {
        val f = file ?: return
        val o = JSONObject()
        val cs = JSONObject()
        for ((id, c) in s.chats) cs.put(id, JSONObject().put("last", c.last).put("mine", c.mine).put("peer", c.peer).put("end", c.endReached).put("req", c.req)
            .put("m", JSONArray(c.msgs.takeLast(KEEP).map { it.toJson() })))
        o.put("chats", cs).put("pinned", JSONArray(s.pinned.toList()))
        val fo = JSONObject(); for ((k, v) in s.folders) fo.put(k, JSONArray(v.toList())); o.put("folders", fo)
        val hi = JSONObject(); for ((k, v) in s.hidden) hi.put(k, v); o.put("hidden", hi)
        val tmp = File(f.parentFile, "chats.json.tmp")
        tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        val chats = HashMap<String, Chat>()
        o.optJSONObject("chats")?.let { cs ->
            for (id in cs.keys()) {
                val c = cs.getJSONObject(id); val a = c.optJSONArray("m") ?: JSONArray()
                chats[id] = Chat(id, (0 until a.length()).map { a.getJSONObject(it).toMsg() }, c.optLong("last"), c.optLong("mine"), c.optLong("peer"), endReached = c.optBoolean("end"), req = c.optBoolean("req"))
            }
        }
        val pinned = o.optJSONArray("pinned")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        val folders = HashMap<String, Set<String>>()
        o.optJSONObject("folders")?.let { fo -> for (k in fo.keys()) { val a = fo.getJSONArray(k); folders[k] = (0 until a.length()).map { a.getString(it) }.toSet() } }
        val hidden = HashMap<String, Long>()
        o.optJSONObject("hidden")?.let { hi -> for (k in hi.keys()) hidden[k] = hi.optLong(k) }
        // اگر قبل از تمام‌شدن خواندن، پیامی رسیده، آن را نگه می‌داریم و فقط چیزهای نداشته را از فایل اضافه می‌کنیم
        state.update { cur -> ChatsState(chats + cur.chats, pinned + cur.pinned, folders + cur.folders, hidden + cur.hidden, cur.found, cur.foundQ, cur.err, cur.errId) }
    }

    private fun merge(old: List<Msg>, incoming: List<Msg>): List<Msg> {
        val byId = LinkedHashMap<String, Msg>(); val pend = ArrayList<Msg>()
        for (m in old) if (m.id.isEmpty()) pend.add(m) else byId[m.id] = m
        for (m in incoming) { val o = byId[m.id]; byId[m.id] = if (o == null) m else m.copy(cid = o.cid.ifEmpty { m.cid }, del = m.del || o.del, x = if (m.del || o.del) "" else m.x) }
        val done = byId.values.mapNotNull { it.cid.ifEmpty { null } }.toSet()
        return byId.values.sortedBy { it.seq }.takeLast(KEEP) + pend.filter { it.cid !in done }.sortedBy { it.ts }
    }

    private fun ChatsState.put(id: String, f: (Chat) -> Chat): ChatsState { val c = f(chats[id] ?: Chat(id)); return copy(chats = chats + (id to c)) }

    fun onNew(j: JSONObject) {
        val id = j.optString("chat"); val m = j.toMsg()
        val isReq = j.optInt("req") == 1
        upd { s -> val s2 = s.put(id) { c -> c.copy(msgs = merge(c.msgs, listOf(m)), last = maxOf(c.last, m.seq), typingAt = 0, req = if (isReq) true else c.req) }
            if ((s2.hidden[id] ?: Long.MAX_VALUE) < m.seq) s2.copy(hidden = s2.hidden - id) else s2 }
    }

    fun onAck(j: JSONObject) {
        val id = j.optString("chat"); val cid = j.optString("cid"); val m = j.toMsg(cid)
        upd { s -> s.put(id) { c -> c.copy(msgs = merge(c.msgs.filterNot { it.id.isEmpty() && it.cid == cid }, listOf(m)), last = maxOf(c.last, m.seq), mine = maxOf(c.mine, m.seq)) } }
    }

    /** true = هنوز پیام جامانده هست، دوباره sync بخواه */
    fun onBatch(j: JSONObject): Boolean {
        val a = j.optJSONArray("list") ?: return false
        var more = false
        upd { s0 ->
            var s = s0
            for (i in 0 until a.length()) {
                val jo = a.getJSONObject(i); val id = jo.optString("chat"); val ms = jo.optJSONArray("msgs") ?: JSONArray()
                val list = (0 until ms.length()).map { k -> ms.getJSONObject(k).toMsg() }
                val dels = jo.optJSONArray("dels")?.let { d -> (0 until d.length()).map { k -> d.getString(k) }.toSet() } ?: emptySet()
                if (jo.optBoolean("more")) more = true
                if (list.isEmpty() && dels.isEmpty() && s.chats[id] == null && jo.optLong("last") == 0L) continue
                s = s.put(id) { c ->
                    val base = c.msgs.map { m -> if (m.id in dels) m.copy(del = true, x = "") else m }
                    c.copy(msgs = merge(base, list), last = maxOf(c.last, jo.optLong("last")), mine = maxOf(c.mine, jo.optLong("mine")), peer = maxOf(c.peer, jo.optLong("peer")), req = jo.optBoolean("req", c.req))
                }
            }
            s
        }
        return more
    }

    fun onHist(j: JSONObject) {
        val id = j.optString("chat"); val ms = j.optJSONArray("msgs") ?: JSONArray()
        val list = (0 until ms.length()).map { ms.getJSONObject(it).toMsg() }
        upd { s -> s.put(id) { c -> c.copy(msgs = merge(c.msgs, list), endReached = j.optBoolean("end")) } }
    }

    fun onReq(j: JSONObject) { val id = j.optString("chat"); val r = j.optBoolean("req"); upd { s -> s.put(id) { c -> c.copy(req = r) } } }

    fun onDel(j: JSONObject) {
        val id = j.optString("chat"); val mid = j.optString("id")
        upd { s -> s.put(id) { c -> c.copy(msgs = c.msgs.map { m -> if (m.id == mid) m.copy(del = true, x = "") else m }) } }
    }

    fun onRd(j: JSONObject) { val id = j.optString("chat"); val up = j.optLong("upto"); upd { s -> s.put(id) { c -> c.copy(peer = maxOf(c.peer, up)) } } }

    fun onTyping(j: JSONObject) { val id = j.optString("chat"); val by = j.optString("f"); state.update { s -> s.put(id) { c -> c.copy(typingAt = System.currentTimeMillis(), typingBy = by) } } }

    fun onFound(j: JSONObject) {
        val a = j.optJSONArray("list") ?: JSONArray()
        val l = (0 until a.length()).map { val p = a.getJSONObject(it); Prof(p.optString("n"), p.optString("dn"), p.optString("bio"), p.optString("role", "member"), p.optLong("since"), p.optBoolean("on")) }
        state.update { it.copy(found = l, foundQ = j.optString("q")) }
    }

    fun onErr(j: JSONObject) {
        val cid = j.optString("cid")
        // پیام ردشده توسط فیلتر قابل «ارسال دوباره» نیست: از صف پاک می‌شود و فقط خطا نشان داده می‌شود
        if (j.optString("code") == "filtered" && cid.isNotEmpty()) {
            upd { s -> s.copy(chats = s.chats.mapValues { (_, c) -> c.copy(msgs = c.msgs.filterNot { it.id.isEmpty() && it.cid == cid }) }, err = j.optString("m"), errId = s.errId + 1) }
            return
        }
        upd { s ->
            val chats = if (cid.isEmpty()) s.chats else s.chats.mapValues { (_, c) -> if (c.msgs.any { it.id.isEmpty() && it.cid == cid }) c.copy(msgs = c.msgs.map { m -> if (m.id.isEmpty() && m.cid == cid) m.copy(st = 2) else m }) else c }
            s.copy(chats = chats, err = j.optString("m"), errId = s.errId + 1)
        }
    }

    // ----- کارهای کاربر -----
    fun queueSend(id: String, text: String, reply: Msg?): Msg {
        val m = Msg(cid = UUID.randomUUID().toString(), f = me, ts = System.currentTimeMillis(), x = text, st = 0,
            rp = reply?.id.orEmpty(), rt = reply?.x.orEmpty().take(80), rf = reply?.f.orEmpty())
        upd { s -> s.put(id) { c -> c.copy(msgs = c.msgs + m, req = false) } }
        return m
    }
    fun retry(id: String, cid: String) = upd { s -> s.put(id) { c -> c.copy(msgs = c.msgs.map { if (it.cid == cid && it.id.isEmpty()) it.copy(st = 0) else it }) } }
    fun dropPending(id: String, cid: String) = upd { s -> s.put(id) { c -> c.copy(msgs = c.msgs.filterNot { it.cid == cid && it.id.isEmpty() }) } }
    fun markRead(id: String, upto: Long) = upd { s -> s.put(id) { c -> if (upto > c.mine) c.copy(mine = upto) else c } }
    fun ensure(id: String) { if (state.value.chats[id] == null) upd { s -> s.put(id) { it } } }
    fun pending(): List<Pair<String, Msg>> = state.value.chats.flatMap { (id, c) -> c.msgs.filter { it.id.isEmpty() && it.st == 0 && it.k != "img" }.map { id to it } }
    /** عکس در حال ارسال: پیام محلی با فایل فشرده‌شده؛ آپلود و ارسال را سرویس انجام می‌دهد */
    fun queueMedia(id: String, caption: String, path: String): Msg {
        val m = Msg(cid = UUID.randomUUID().toString(), f = me, ts = System.currentTimeMillis(), x = caption, st = 0, k = "img", vby = path, ms = "up")
        upd { s -> s.put(id) { c -> c.copy(msgs = c.msgs + m, req = false) } }
        return m
    }
    fun onPend(j: JSONObject) { val cid = j.optString("cid"); val st = j.optString("st"); upd { s -> s.copy(chats = s.chats.mapValues { (_, c) -> if (c.msgs.any { it.id.isEmpty() && it.cid == cid }) c.copy(msgs = c.msgs.map { m -> if (m.id.isEmpty() && m.cid == cid) m.copy(ms = st) else m }) else c }) } }
    fun onRej(j: JSONObject) { val cid = j.optString("cid"); upd { s -> s.copy(chats = s.chats.mapValues { (_, c) -> c.copy(msgs = c.msgs.filterNot { it.id.isEmpty() && it.cid == cid }) }, err = j.optString("m"), errId = s.errId + 1) } }
    fun cursors(): Map<String, Long> = state.value.chats.mapValues { (_, c) -> c.msgs.filter { it.id.isNotEmpty() }.maxOfOrNull { it.seq } ?: 0L }
    /** پاک کردن کش پیام‌ها (پین/پوشه/پنهان‌ها می‌مانند)؛ بعدش sync دوباره ۳۰ پیام آخر را می‌آورد */
    fun clearAll(ctx: Context) {
        upd { s -> s.copy(chats = emptyMap()) }
        runCatching { File(ctx.applicationContext.filesDir, "chats.json").delete() }
    }
    private val animated = LinkedHashSet<String>()
    /** true اگر برای این پیام هنوز انیمیشن ویژه اجرا نشده (هر پیام فقط یک بار) */
    @Synchronized fun markAnimated(id: String): Boolean { val fresh = animated.add(id); if (animated.size > 500) animated.remove(animated.first()); return fresh }
    fun say(m: String) = state.update { it.copy(err = m, errId = it.errId + 1) }
    fun drop(id: String) {
        upd { s -> s.copy(chats = s.chats - id, pinned = s.pinned - id, hidden = s.hidden - id, folders = s.folders.mapValues { (_, v) -> v - id }) }
        if (open.value == id) open.value = null
    }
    /** فهرست گروه‌های سرور معتبر است؛ گروه‌هایی که دیگر عضوشان نیستی (مثلاً وقتی آفلاین بودی بیرونت کردند) از گوشی پاک می‌شوند */
    fun retainGroups(ids: Set<String>) { state.value.chats.keys.filter { it.startsWith("c:") && it !in ids }.forEach { drop(it) } }
    fun togglePin(id: String) = upd { it.copy(pinned = if (id in it.pinned) it.pinned - id else it.pinned + id) }
    fun hide(id: String) = upd { s -> val c = s.chats[id]; s.copy(hidden = s.hidden + (id to (c?.last ?: 0L)), chats = s.chats + (id to (c ?: Chat(id)).copy(msgs = emptyList(), mine = c?.last ?: 0L)), pinned = s.pinned - id) }
    fun newFolder(name: String) = upd { if (name.isBlank() || name in it.folders) it else it.copy(folders = it.folders + (name.trim().take(20) to emptySet())) }
    fun deleteFolder(name: String) = upd { it.copy(folders = it.folders - name) }
    fun toggleInFolder(name: String, id: String) = upd { s -> val cur = s.folders[name] ?: emptySet(); s.copy(folders = s.folders + (name to (if (id in cur) cur - id else cur + id))) }
}

// ───────────────────────── کمکی‌های UI ─────────────────────────
private fun String.limitCp(max: Int): String = if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))
private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
private fun dayOrTime(t: Long): String {
    val now = System.currentTimeMillis()
    return if (now - t < 20 * 3600_000L) hhmm(t) else SimpleDateFormat("MM/dd", Locale.US).format(Date(t))
}
private val contentDir = TextStyle(textDirection = TextDirection.Content)

private fun chatTitle(s: Ui, id: String): String {
    if (id == "g") return "چت جهانی"
    if (id.startsWith("c:")) return Grp.name(id.drop(2))
    val k = Chats.peerKey(id)
    val p = s.profiles[k]
    return if (p != null) label(s, p.n) else k
}

private fun unreadOf(c: Chat): Int = c.msgs.count { it.id.isNotEmpty() && it.seq > c.mine && !it.f.equals(Chats.me, true) && !it.del }

private fun preview(c: Chat): String {
    val m = c.msgs.lastOrNull() ?: return "هنوز پیامی نیست"
    return if (m.del) "این پیام حذف شد" else if (m.k == "call") "ویس‌کال «${m.vid}»" else if (m.k == "ginv") "دعوت به گروه «${m.vby}»" else if (m.k == "img") ("عکس" + if (m.x.isNotEmpty()) ": " + m.x.replace('\n', ' ') else "") else m.x.replace('\n', ' ')
}

@Composable
private fun ChatAvatar(s: Ui, id: String, size: androidx.compose.ui.unit.Dp) {
    if (id == "g" || id.startsWith("c:")) Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(if (id == "g") listOf(Color(0xFF5CC3FF), Sky.Blue2) else listOf(Color(0xFFA48CFF), Color(0xFF6B5CE7)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Groups, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    } else Avatar(s.profiles[Chats.peerKey(id)]?.n ?: Chats.peerKey(id).ifEmpty { "?" }, size, Sky.Blue)
}

// ───────────────────────── منوی چت‌ها ─────────────────────────
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(s: Ui) {
    val c = LocalContext.current
    LaunchedEffect(Unit) { Chats.init(c) }
    val st by Chats.state.collectAsState()
    val open by Chats.open.collectAsState()
    var folder by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    var folderFor by remember { mutableStateOf<String?>(null) }
    var newChat by remember { mutableStateOf(false) }
    var newFolderDlg by remember { mutableStateOf(false) }
    val folderScroll = rememberScrollState()
    var page by rememberSaveable { mutableStateOf("") }
    val gst by Grp.state.collectAsState()
    var plusMenu by remember { mutableStateOf(false) }
    var createDlg by remember { mutableStateOf(false) }
    var joinDlg by remember { mutableStateOf(false) }
    var staffDlg by remember { mutableStateOf(false) }

    open?.let { id -> BackHandler { Chats.open.value = null }; ChatScreen(s, st, id) { Chats.open.value = null }; return }

    if (page.startsWith("ginfo:")) { BackHandler { page = "" }; GroupInfoScreen(s, page.drop(6)) { page = "" }; return }
    if (page == "friends") { BackHandler { page = "" }; FriendsScreen(s) { page = "" }; return }

    // چت جهانی همیشه هست (مگر حذف شده باشد)؛ بقیه فقط اگر پیامی داشته باشند یا حذف نشده باشند
    val ids = (st.chats.keys + "g" + gst.list.map { "c:" + it.id }).filter { id ->
        val hid = st.hidden[id]; val c0 = st.chats[id]
        (hid == null || (c0?.last ?: 0L) > hid) && (id == "g" || id.startsWith("c:") || (c0?.msgs?.isNotEmpty() == true))
    }.filter { folder.isEmpty() || it in (st.folders[folder] ?: emptySet()) }
    val sorted = ids.sortedWith(compareByDescending<String> { st.chats[it]?.req == true }.thenByDescending { it in st.pinned }.thenByDescending { st.chats[it]?.msgs?.lastOrNull()?.ts ?: 0L })
    val reqCount = sorted.count { st.chats[it]?.req == true }

    LaunchedEffect(st.chats.keys, s.state) {
        if (s.state == St.Connected) {
            val miss = st.chats.keys.map { Chats.peerKey(it) }.filter { it.isNotEmpty() && s.profiles[it] == null }
            if (miss.isNotEmpty()) VoiceService.cmd(c, VoiceService.CHAT, "op" to "profs", "names" to miss.joinToString("\n"))
        }
    }

    if (createDlg) CreateGroupDlg { createDlg = false }
    if (joinDlg) JoinGroupDlg { joinDlg = false }
    if (staffDlg) StaffGroupsDlg({ staffDlg = false }) { gid -> staffDlg = false; page = "ginfo:$gid" }
    if (plusMenu) AlertDialog(onDismissRequest = { plusMenu = false }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("جدید", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                TextButton({ plusMenu = false; newChat = true }) { Text("چت خصوصی جدید", color = Sky.Blue) }
                TextButton({ plusMenu = false; createDlg = true }) { Text("ساخت گروه", color = Sky.Blue) }
                TextButton({ plusMenu = false; joinDlg = true }) { Text("پیوستن به گروه با کد", color = Sky.Blue) }
                if (s.role == "owner" || s.role == "admin") TextButton({ plusMenu = false; staffDlg = true }) { Text("همه‌ی گروه‌ها (مدیر برنامه)", color = Sky.Warn) }
            }
        }, confirmButton = {}, dismissButton = { TextButton({ plusMenu = false }) { Text("بستن", color = Sky.Ink2) } })
    if (newChat) NewChatDialog(s, st, onDismiss = { newChat = false }) { name -> newChat = false; val id = Chats.dmId(s.name, name); Chats.ensure(id); Chats.open.value = id }
    menu?.let { id ->
        AlertDialog(onDismissRequest = { menu = null }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text(chatTitle(s, id), color = Sky.Ink, fontWeight = FontWeight.Bold, maxLines = 1) },
            text = {
                Column {
                    TextButton({ Chats.togglePin(id); menu = null }) { Text(if (id in st.pinned) "برداشتن پین" else "پین کردن", color = Sky.Blue) }
                    TextButton({ folderFor = id; menu = null }) { Text("پوشه‌ها…", color = Sky.Blue) }
                    if (id.startsWith("c:")) {
                        TextButton({ page = "ginfo:" + id.drop(2); menu = null }) { Text("اطلاعات و مدیریت گروه", color = Sky.Blue) }
                        TextButton({ VoiceService.cmd(c, VoiceService.GRP, "op" to "leave", "id" to id.drop(2)); menu = null }) { Text("ترک گروه", color = Sky.Bad) }
                    } else TextButton({ Chats.hide(id); menu = null }) { Text("حذف چت (فقط از روی این گوشی)", color = Sky.Bad) }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ menu = null }) { Text("بستن", color = Sky.Ink2) } })
    }
    folderFor?.let { id ->
        AlertDialog(onDismissRequest = { folderFor = null }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text("پوشه‌ها", color = Sky.Ink, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (st.folders.isEmpty()) Text("هنوز پوشه‌ای نداری. از بالای لیست «پوشه‌ی جدید» را بزن.", color = Sky.Ink2, fontSize = 12.sp)
                    st.folders.forEach { (name, members) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in members, { Chats.toggleInFolder(name, id) })
                            Text(name, color = Sky.Ink, modifier = Modifier.weight(1f), style = contentDir)
                            Icon(Icons.Rounded.Delete, "حذف پوشه", tint = Sky.Bad, modifier = Modifier.size(22.dp).clickable { Chats.deleteFolder(name); if (folder == name) folder = "" })
                        }
                    }
                }
            }, confirmButton = { TextButton({ folderFor = null }) { Text("تمام", color = Sky.Blue, fontWeight = FontWeight.Bold) } })
    }
    if (newFolderDlg) {
        var nm by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolderDlg = false }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text("پوشه‌ی جدید", color = Sky.Ink, fontWeight = FontWeight.Bold) },
            text = { Field(nm, { nm = it.limitCp(20) }, "اسم پوشه", Icons.Rounded.Folder) },
            confirmButton = { TextButton({ if (nm.isNotBlank()) { Chats.newFolder(nm); newFolderDlg = false } }) { Text("بساز", color = Sky.Blue, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ newFolderDlg = false }) { Text("انصراف", color = Sky.Ink2) } })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("چت‌ها", color = Sky.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Person, "دوستان", tint = Sky.Ink2, modifier = Modifier.size(28.dp).clickable { page = "friends" })
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Folder, "پوشه‌ی جدید", tint = Sky.Ink2, modifier = Modifier.size(28.dp).clickable { newFolderDlg = true })
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Edit, "چت جدید", tint = Sky.Blue, modifier = Modifier.size(28.dp).clickable(enabled = s.state == St.Connected) { plusMenu = true })
        }
        if (s.state != St.Connected) Chip(Icons.Rounded.Warning, "به سرور وصل نیستی؛ پیام‌ها در صف می‌مانند و بعداً می‌روند", Sky.Warn)
        if (st.folders.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(folderScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val all = listOf("" to "همه") + st.folders.keys.map { it to it }
            all.forEach { (k, label) ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (folder == k) Sky.Blue else Sky.Chip).clickable { folder = k }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(label, color = if (folder == k) Color.White else Sky.Ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sorted.isEmpty()) item { Text("چتی در این پوشه نیست.", color = Sky.Ink2, fontSize = 13.sp) }
            if (reqCount > 0) item(key = "reqhdr") { Text("درخواست‌های پیام ($reqCount) — از غریبه‌ها؛ تا قبول نکنی فقط ۳ پیام می‌توانند بفرستند", color = Sky.Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            items(sorted, key = { it }) { id ->
                val ch = st.chats[id] ?: Chat(id); val un = unreadOf(ch)
                Glass(Modifier.fillMaxWidth().combinedClickableCompat({ Chats.open.value = id }, { menu = id }), pad = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatAvatar(s, id, 48.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (id in st.pinned) Icon(Icons.Rounded.PushPin, null, tint = Sky.Ink2, modifier = Modifier.size(14.dp))
                                Text(chatTitle(s, id), color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = contentDir)
                                ch.msgs.lastOrNull()?.let { Text(dayOrTime(it.ts), color = Sky.Ink2, fontSize = 11.sp) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val typing = System.currentTimeMillis() - ch.typingAt < 5000
                                Text(if (typing) "در حال نوشتن…" else preview(ch), color = if (typing) Sky.Ok else Sky.Ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = contentDir)
                                if (un > 0) Box(Modifier.clip(CircleShape).background(Sky.Blue).padding(horizontal = 7.dp, vertical = 2.dp)) { Text(if (un > 99) "99+" else "$un", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatDialog(s: Ui, st: ChatsState, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = LocalContext.current
    var q by remember { mutableStateOf("") }
    LaunchedEffect(q) { if (q.trim().length >= 2) { delay(450); VoiceService.cmd(c, VoiceService.CHAT, "op" to "find", "q" to q.trim()) } }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("چت جدید", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(q, { q = it.limitCp(32) }, "اسم بازیکن (حداقل ۲ حرف)", Icons.Rounded.Person)
                if (q.trim().length >= 2) st.found.filter { !it.n.equals(s.name, true) }.take(10).forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(p.n) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(p.n, 36.dp, Sky.Blue)
                        Text(if (p.dn.isNotBlank()) "${p.dn} (${p.n})" else p.n, color = Sky.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentDir)
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("بستن", color = Sky.Ink2) } })
}

// ───────────────────────── صفحه‌ی گفتگو ─────────────────────────
@Composable
private fun ChatScreen(s: Ui, st: ChatsState, id: String, onBack: () -> Unit) {
    val c = LocalContext.current
    val clip = LocalClipboardManager.current
    val chat = st.chats[id] ?: Chat(id)
    val isDm = id.startsWith("d:")
    val isGroup = id.startsWith("c:")
    var info by remember { mutableStateOf(false) }
    val gst by Grp.state.collectAsState()
    var text by rememberSaveable(id) { mutableStateOf("") }
    var viewMsg by remember { mutableStateOf<Msg?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val cap = text.trim(); text = ""
            Thread {
                val f = MediaX.prepare(c, uri)
                if (f == null) Chats.say("این عکس خوانده نشد یا بزرگ‌تر از ۱۰ مگابایت است.")
                else { val m = Chats.queueMedia(id, cap, f.absolutePath); VoiceService.cmd(c, VoiceService.MEDIA, "op" to "send", "cid" to m.cid, "chat" to id, "file" to f.absolutePath, "caption" to cap) }
            }.start()
        }
    }
    var replyTo by remember(id) { mutableStateOf<Msg?>(null) }
    var sel by remember { mutableStateOf<Msg?>(null) }
    var lastTyping by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val typing = isDm && now - chat.typingAt < 5000
    val staff = s.role == "owner" || s.role == "admin"
    val soc by Social.state.collectAsState()
    val blockedKeys = soc.blocked.map { it.n.lowercase() }.toSet()
    var confirmBlock by remember { mutableStateOf(false) }
    val peerN = s.profiles[Chats.peerKey(id)]?.n ?: Chats.peerKey(id)

    // خوانده شد: وقتی این گفتگو باز است و پیام تازه‌ای هست
    LaunchedEffect(chat.last, s.state) {
        if (s.state == St.Connected && chat.last > chat.mine) {
            Chats.markRead(id, chat.last)
            VoiceService.cmd(c, VoiceService.CHAT, "op" to "read", "chat" to id, "upto" to chat.last.toString())
        }
    }
    // وضعیت زنده‌ی کارت‌های ویس این گفتگو: هر ۵ ثانیه، دسته‌ای و فقط وقتی گفتگو باز است
    val callIds = chat.msgs.filter { it.k == "call" && !it.del }.map { it.vid }.distinct().takeLast(20)
    LaunchedEffect(callIds, s.state) {
        while (s.state == St.Connected && callIds.isNotEmpty()) {
            VoiceService.cmd(c, VoiceService.VOICE, "op" to "stat", "ids" to callIds.joinToString("\n"))
            delay(5000)
        }
    }
    LaunchedEffect(text) {
        if (isDm && text.isNotBlank() && s.state == St.Connected && System.currentTimeMillis() - lastTyping > 3000) {
            lastTyping = System.currentTimeMillis()
            VoiceService.cmd(c, VoiceService.CHAT, "op" to "typing", "chat" to id)
        }
    }

    if (confirmBlock) AlertDialog(onDismissRequest = { confirmBlock = false }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("بلاک $peerN؟", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = { Text("پیوی هر دو طرف بسته می‌شود و دوستی (اگر هست) پاک می‌شود. از «دوستان ← بلاک‌شده‌ها» رفع بلاک می‌کنی.", color = Sky.Ink2, fontSize = 13.sp) },
        confirmButton = { TextButton({ VoiceService.cmd(c, VoiceService.SOC, "op" to "blk", "name" to peerN); Chats.hide(id); confirmBlock = false; onBack() }) { Text("بلاک کن", color = Sky.Bad, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirmBlock = false }) { Text("انصراف", color = Sky.Ink2) } })

    var reportMsg by remember { mutableStateOf<Msg?>(null) }
    reportMsg?.let { rm ->
        var why by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { reportMsg = null }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text("گزارش ${label(s, rm.f)}", color = Sky.Ink, fontWeight = FontWeight.Bold, maxLines = 1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(why, { why = it.limitCp(300) }, Modifier.fillMaxWidth(), maxLines = 4, placeholder = { Text("چه اتفاقی افتاد؟") })
                    Text("۲۰ پیام آخر همین گفتگو به‌عنوان مدرک فقط برای مدیر برنامه ثبت می‌شود. به خود فرد گفته نمی‌شود.", color = Sky.Ink2, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton({ if (why.isNotBlank()) { VoiceService.cmd(c, VoiceService.TIX, "op" to "new", "kind" to "report", "target" to rm.f, "chat" to id, "x" to why.trim()); reportMsg = null } }) { Text("ارسال گزارش", color = Sky.Bad, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ reportMsg = null }) { Text("انصراف", color = Sky.Ink2) } })
    }

    sel?.let { m ->
        val mine = m.f.equals(s.name, true)
        AlertDialog(onDismissRequest = { sel = null }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text("پیام", color = Sky.Ink, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (m.st == 2) {
                        TextButton({ Chats.retry(id, m.cid); VoiceService.cmd(c, VoiceService.CHAT, "op" to "flush"); sel = null }) { Text("ارسال دوباره", color = Sky.Blue) }
                        TextButton({ Chats.dropPending(id, m.cid); sel = null }) { Text("حذف", color = Sky.Bad) }
                    } else if (!m.del) {
                        if (m.id.isNotEmpty() && m.k != "call" && m.k != "ginv") TextButton({ replyTo = m; sel = null }) { Text("پاسخ", color = Sky.Blue) }
                        if (m.k != "call" && m.k != "ginv" && m.k != "img") TextButton({ clip.setText(AnnotatedString(m.x)); sel = null }) { Text("کپی", color = Sky.Blue) }
                        if (!mine && m.id.isNotEmpty() && m.r != "bot") TextButton({ reportMsg = m; sel = null }) { Text("گزارش", color = Sky.Bad) }
                        if (m.id.isNotEmpty() && (mine || (id == "g" && staff))) TextButton({ VoiceService.cmd(c, VoiceService.CHAT, "op" to "del", "chat" to id, "id" to m.id); sel = null }) { Text("حذف برای همه", color = Sky.Bad) }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ sel = null }) { Text("بستن", color = Sky.Ink2) } })
    }

    viewMsg?.let { vm -> ImageViewer(vm) { viewMsg = null } }
    if (info) { BackHandler { info = false }; GroupInfoScreen(s, id.drop(2)) { info = false }; return }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.ArrowBack, "بازگشت", tint = Sky.Ink, modifier = Modifier.size(28.dp).clickable { onBack() })
            ChatAvatar(s, id, 40.dp)
            Column(Modifier.weight(1f).then(if (isGroup) Modifier.clickable { info = true } else Modifier)) {
                Text(chatTitle(s, id), color = Sky.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentDir)
                Text(if (typing) "در حال نوشتن…" else if (isDm) Chats.peerKey(id) else if (isGroup) "${gst.list.firstOrNull { it.id == id.drop(2) }?.n ?: 0} عضو · لمس برای اطلاعات" else "همه‌ی بازیکن‌ها", color = if (typing) Sky.Ok else Sky.Ink2, fontSize = 12.sp, maxLines = 1)
            }
            if (s.group.isNotEmpty() && s.state == St.Connected && (isDm || isGroup)) Icon(Icons.Rounded.Call, if (isDm && Social.isFriend(peerN)) "دعوت به ویس" else "فرستادن کارت ویس", tint = Sky.Ok, modifier = Modifier.size(26.dp).clickable {
                if (isDm && Social.isFriend(peerN)) VoiceService.cmd(c, VoiceService.VOICE, "op" to "invite", "to" to peerN)
                else VoiceService.cmd(c, VoiceService.VOICE, "op" to "card", "chat" to id)
            })
            if (isDm && !Social.isFriend(peerN) && !Social.isPending(peerN)) Icon(Icons.Rounded.PersonAdd, "افزودن دوست", tint = Sky.Blue, modifier = Modifier.size(26.dp).clickable { VoiceService.cmd(c, VoiceService.SOC, "op" to "req", "name" to peerN) })
            if (isDm) Icon(Icons.Rounded.Block, "بلاک", tint = Sky.Bad, modifier = Modifier.size(24.dp).clickable { confirmBlock = true })
        }
        val list = chat.msgs.asReversed().filter { isDm || it.f.lowercase() !in blockedKeys }
        val listState = rememberLazyListState()
        // پیام تازه آمد و پایین لیست بودیم ← به آخر برو
        LaunchedEffect(chat.msgs.lastOrNull()?.cid, chat.msgs.lastOrNull()?.id) { if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), state = listState, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.cid.ifEmpty { it.id } }) { m -> Bubble(s, chat, m, isDm, { viewMsg = it }) { sel = m } }
            item(key = "older") {
                val first = chat.msgs.firstOrNull { it.id.isNotEmpty() }
                if (first != null && first.seq > 1 && !chat.endReached) {
                    LaunchedEffect(first.seq) { VoiceService.cmd(c, VoiceService.CHAT, "op" to "hist", "chat" to id, "before" to first.seq.toString()) }
                    Text("در حال بارگذاری پیام‌های قدیمی‌تر…", color = Sky.Ink2, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(8.dp))
                }
            }
        }
        if (chat.req) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Sky.Chip).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("این بازیکن دوستت نیست. پیام‌هایش «درخواست پیام» است.", color = Sky.Ink2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton({ VoiceService.cmd(c, VoiceService.CHAT, "op" to "accept", "chat" to id); Chats.onReq(JSONObject().put("chat", id).put("req", false)) }) { Text("قبول", color = Sky.Ok, fontWeight = FontWeight.Bold) }
            TextButton({ confirmBlock = true }) { Text("بلاک", color = Sky.Bad, fontWeight = FontWeight.Bold) }
        }
        if (st.err.isNotEmpty() && chat.msgs.any { it.st == 2 }) Text(st.err, color = Sky.Bad, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Sky.Chip).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(30.dp).background(Sky.Blue))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(label(s, r.f), color = Sky.Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(r.x, color = Sky.Ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentDir)
                }
                Icon(Icons.Rounded.Close, "لغو پاسخ", tint = Sky.Ink2, modifier = Modifier.size(22.dp).clickable { replyTo = null })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.state == St.Connected) Icon(Icons.Rounded.Image, "فرستادن عکس", tint = Sky.Blue, modifier = Modifier.size(34.dp).padding(bottom = 6.dp).clickable { picker.launch("image/*") })
            OutlinedTextField(text, { text = it.limitCp(500) }, Modifier.weight(1f), maxLines = 4, placeholder = { Text("پیام…") },
                shape = RoundedCornerShape(22.dp), textStyle = contentDir.copy(color = Sky.Ink, fontSize = 15.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (text.isBlank()) Sky.Blue.copy(alpha = 0.4f) else Sky.Blue)
                .clickable(enabled = text.isNotBlank()) {
                    Chats.queueSend(id, text.trim(), replyTo)
                    VoiceService.cmd(c, VoiceService.CHAT, "op" to "flush")
                    text = ""; replyTo = null
                }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Send, "ارسال", tint = Color.White) }
        }
    }
}

@Composable
private fun Bubble(s: Ui, chat: Chat, m: Msg, isDm: Boolean, onOpenImage: (Msg) -> Unit, onTap: () -> Unit) {
    val mine = m.f.equals(s.name, true)
    if (m.k == "call" && !m.del) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) { CallCard(s, m, onTap) }
        return
    }
    if (m.k == "ginv" && !m.del) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) { GroupInviteCard(s, m, onTap) }
        return
    }
    val bg = if (mine) Brush.linearGradient(listOf(Color(0xFF45B6FF), Color(0xFF1E8CFF))) else Brush.linearGradient(listOf(Sky.Card, Sky.Card))
    val fg = if (mine) Color.White else Sky.Ink
    val fg2 = if (mine) Color.White.copy(alpha = 0.8f) else Sky.Ink2
    // ظاهر ویژه‌ی مالک/ادمین برنامه: حاشیه‌ی گرادیانی ملایم + نشان. انیمیشن کوتاه فقط یک بار برای پیام تازه‌ای که واقعاً دیده می‌شود
    // (آیتم خارج از صفحه ساخته نمی‌شود/متوقف می‌شود)، و با «کاهش انیمیشن» سیستم یا حالت باتری کم خاموش است.
    val tone = when (m.r) { "owner" -> listOf(Color(0xFFFFC857), Color(0xFFFF8A3D)); "admin" -> listOf(Color(0xFF7AA2FF), Color(0xFFB07CFF)); "bot" -> listOf(Color(0xFF4FD8FF), Color(0xFF3DDC97)); else -> null }
    val ctx = LocalContext.current
    val prog = remember(m.cid, m.id) { Animatable(1f) }
    if (tone != null && m.id.isNotEmpty()) LaunchedEffect(m.id) {
        if (System.currentTimeMillis() - m.ts < 60_000L && Chats.markAnimated(m.id) && motionAllowed(ctx)) { prog.snapTo(0f); prog.animateTo(1f, tween(900)) }
    }
    val border = if (tone == null) Modifier else Modifier.drawWithContent {
        drawContent()
        val p = prog.value
        drawRoundRect(
            brush = Brush.linearGradient(tone.map { it.copy(alpha = 0.35f + 0.65f * p) }, start = Offset(0f, 0f), end = Offset(size.width * (0.4f + 0.6f * p), size.height)),
            cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(1.6.dp.toPx())
        )
    }
    // «من» سمت راست، بقیه سمت چپ (برنامه راست‌به‌چپ است، پس Start = راست)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) {
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(bg).then(border).clickable { onTap() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (tone != null || m.gr.isNotEmpty() || (!mine && !isDm)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (m.r) { "owner" -> Icon(Icons.Rounded.WorkspacePremium, null, tint = if (mine) Color.White else Sky.Warn, modifier = Modifier.size(15.dp)); "admin" -> Icon(Icons.Rounded.Shield, null, tint = if (mine) Color.White else Sky.Blue2, modifier = Modifier.size(15.dp)); "bot" -> Icon(Icons.Rounded.SmartToy, null, tint = Sky.Ok, modifier = Modifier.size(15.dp)); else -> {} }
                if (!mine && !isDm) Text(label(s, m.f), color = if (m.r == "owner") Sky.Warn else if (m.r == "admin") Sky.Blue2 else Sky.Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, style = contentDir)
                else if (tone != null) Text(if (m.r == "owner") "مالک برنامه" else if (m.r == "admin") "ادمین برنامه" else "ربات", color = fg2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (m.gr.isNotEmpty()) Text(if (m.gr == "owner") "· مالک گروه" else "· ادمین گروه", color = fg2, fontSize = 10.sp)
            }
            if (m.rp.isNotEmpty() && !m.del) Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(28.dp).background(fg2))
                Column(Modifier.padding(horizontal = 6.dp)) {
                    Text(label(s, m.rf), color = fg2, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(m.rt, color = fg2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentDir)
                }
            }
            if (m.k == "img" && !m.del) { MsgImage(m) { onOpenImage(m) }; if (m.x.isNotEmpty()) Spacer(Modifier.height(4.dp)) }
            if (m.del) Text("این پیام حذف شد", color = fg2, fontSize = 13.sp, fontStyle = FontStyle.Italic)
            else if (m.k != "img" || m.x.isNotEmpty()) Text(m.x, color = fg, fontSize = 15.sp, style = contentDir)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hhmm(m.ts), color = fg2, fontSize = 10.sp)
                if (mine && !m.del) Text(when { m.st == 0 -> "…"; m.st == 2 -> "⚠ ارسال نشد"; isDm && chat.peer >= m.seq -> "✓✓"; else -> "✓" }, color = if (m.st == 2) Color(0xFFFFD6DC) else fg2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// تابع‌های کمکیِ کوچک تا وابسته به API آزمایشی نباشیم
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))

