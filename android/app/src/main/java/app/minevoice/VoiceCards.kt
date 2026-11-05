package app.minevoice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

// ───────────────────────── مدل ─────────────────────────
/** وضعیت زنده‌ی یک ویس‌کال که کارتش داخل چت است. n = تعداد نفرات الان، ts = زمان شروع تماس (ساعت سرور) */
data class VStat(val n: Int, val ts: Long, val lock: Boolean, val priv: Boolean, val by: String, val gone: Boolean, val closed: Boolean)
data class VReq(val id: String, val from: String, val dn: String, val exp: Long)
data class VState(val stats: Map<String, VStat> = emptyMap(), val offset: Long = 0L, val reqs: List<VReq> = emptyList(), val approved: Map<String, Long> = emptyMap())

object VoiceX {
    val state = MutableStateFlow(VState())

    /** offset = اختلاف ساعت سرور با ساعت گوشی؛ تا «مدت زمان تماس» با ساعت اشتباه گوشی خراب نشود */
    fun onStats(j: JSONObject) {
        val o = j.optJSONObject("s") ?: return
        val m = HashMap<String, VStat>()
        for (id in o.keys()) { val x = o.getJSONObject(id); m[id] = VStat(x.optInt("n"), x.optLong("ts"), x.optBoolean("lock"), x.optBoolean("priv"), x.optString("by"), x.optBoolean("gone"), x.optBoolean("closed")) }
        val off = j.optLong("now") - System.currentTimeMillis()
        state.update { it.copy(stats = it.stats + m, offset = off) }
    }

    private fun parse(a: JSONArray?): List<VReq> = if (a == null) emptyList() else (0 until a.length()).map { val o = a.getJSONObject(it); VReq(o.optString("id"), o.optString("from"), o.optString("dn"), o.optLong("exp")) }

    fun onReqs(j: JSONObject) { val l = parse(j.optJSONArray("list")); state.update { it.copy(reqs = l) } }
    fun onReqIn(j: JSONObject) {
        val r = VReq(j.optString("id"), j.optString("from"), j.optString("dn"), j.optLong("exp"))
        state.update { s -> s.copy(reqs = s.reqs.filterNot { it.id == r.id && it.from.equals(r.from, true) } + r) }
    }
    fun onApproved(j: JSONObject) { val id = j.optString("id"); val exp = j.optLong("exp"); state.update { it.copy(approved = it.approved + (id to exp)) } }
}

private fun fmtDur(ms: Long): String {
    val t = ms / 1000; val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ───────────────────────── کارت تماس (هولوگرامی) ─────────────────────────
/**
 * کارت ویس‌کال داخل چت: اسم سازنده، تعداد نفرات (زنده)، مدت زمان تماس (زنده)، و دکمه‌ی ورود.
 * دکمه به حالت تماس بستگی دارد: بپیوند / ورود با رمز / درخواست ورود (خصوصی) / داخل ویس هستی / تماس تمام شد / منقضی شد (دعوت ۲ دقیقه‌ای).
 * درخشش متحرک فقط وقتی کارت روی صفحه ساخته شده، تماس زنده است و «کاهش انیمیشن»/باتری کم نیست.
 */
@Composable
fun CallCard(s: Ui, m: Msg, onTap: () -> Unit) {
    val c = LocalContext.current
    val vs by VoiceX.state.collectAsState()
    val st = vs.stats[m.vid]
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } } // فقط تا وقتی کارت روی صفحه است
    val sNow = now + vs.offset
    val inviteExpired = m.ve > 0 && sNow > m.ve
    val approvedUntil = vs.approved[m.vid] ?: 0L
    val invited = (m.ve > 0 && !inviteExpired) || approvedUntil > sNow
    val ended = st != null && (st.gone || st.n == 0)
    val inside = s.group == m.vid
    val live = st != null && !ended && !inviteExpired
    var ask by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }

    val animate = remember { motionAllowed(c) } && live
    val shift = if (animate) rememberInfiniteTransition(label = "holo").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "shift").value else 0.5f
    val cyan = Color(0xFF4FD8FF); val violet = Color(0xFFA48CFF)

    if (ask) AlertDialog(onDismissRequest = { ask = false }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("رمز ویس «${m.vid}»", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = { Field(pass, { pass = it.take(16) }, "رمز", Icons.Rounded.Lock, password = true) },
        confirmButton = { TextButton({ VoiceService.cmd(c, VoiceService.GROUP, "id" to m.vid, "pass" to pass); ask = false; pass = "" }) { Text("ورود", color = Sky.Blue, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ ask = false }) { Text("انصراف", color = Sky.Ink2) } })

    Column(
        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(cyan.copy(alpha = if (live) 0.16f else 0.06f), violet.copy(alpha = if (live) 0.18f else 0.06f))))
            .clickable { onTap() }
            .drawWithContent {
                drawContent()
                val w = size.width
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(cyan.copy(alpha = if (live) 0.95f else 0.3f), violet.copy(alpha = if (live) 0.95f else 0.3f), cyan.copy(alpha = if (live) 0.95f else 0.3f)),
                        start = Offset(w * (shift - 0.6f) * 2f, 0f), end = Offset(w * (shift + 0.6f) * 2f, size.height)),
                    cornerRadius = CornerRadius(20.dp.toPx()), style = Stroke(1.8.dp.toPx())
                )
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(cyan, violet))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(m.vid, color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("ساخته‌شده توسط ${m.vby}", color = Sky.Ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (st?.lock == true) Icon(Icons.Rounded.Lock, "رمزدار", tint = Sky.Ink2, modifier = Modifier.size(18.dp))
            if (st?.priv == true) Icon(Icons.Rounded.Block, "خصوصی", tint = Sky.Ink2, modifier = Modifier.size(18.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Groups, null, tint = Sky.Ink2, modifier = Modifier.size(18.dp))
                Text(if (st == null) "…" else "${st.n} نفر", color = Sky.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Schedule, null, tint = Sky.Ink2, modifier = Modifier.size(18.dp))
                Text(if (st != null && live && st.ts > 0) fmtDur(sNow - st.ts) else "--:--", color = Sky.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (m.ve > 0 && !inviteExpired && !ended) Text("دعوت‌نامه — ${((m.ve - sNow) / 1000).coerceAtLeast(0)} ثانیه‌ی دیگر معتبر است", color = Sky.Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold)

        val label: String; val enabled: Boolean
        when {
            inside -> { label = "داخل ویس هستی"; enabled = false }
            st == null -> { label = "در حال بررسی…"; enabled = false }
            ended -> { label = "تماس تمام شد"; enabled = false }
            st.closed -> { label = "این ویس بسته شده"; enabled = false }
            inviteExpired && approvedUntil <= sNow -> { label = "دعوت منقضی شد"; enabled = false }
            st.priv && !invited -> { label = "درخواست ورود"; enabled = s.state == St.Connected }
            st.lock && !invited -> { label = "ورود با رمز"; enabled = s.state == St.Connected }
            else -> { label = "بپیوند"; enabled = s.state == St.Connected }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (enabled) Brush.horizontalGradient(listOf(cyan, violet)) else Brush.horizontalGradient(listOf(Color(0xFFD5DCE6), Color(0xFFD5DCE6))))
                .clickable(enabled = enabled) {
                    when {
                        st != null && st.priv && !invited -> VoiceService.cmd(c, VoiceService.VOICE, "op" to "req", "id" to m.vid)
                        st != null && st.lock && !invited -> ask = true
                        else -> VoiceService.cmd(c, VoiceService.GROUP, "id" to m.vid)
                    }
                }.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) { Text(label, color = if (enabled) Color.White else Sky.Ink2, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
    }
}

// ───────────────────────── درخواست‌های ورود به ویس من (برای صاحب ویس) ─────────────────────────
@Composable
fun VoiceRequestsBanner(s: Ui) {
    val c = LocalContext.current
    val vs by VoiceX.state.collectAsState()
    LaunchedEffect(s.state) { if (s.state == St.Connected) VoiceService.cmd(c, VoiceService.VOICE, "op" to "reqs") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vs.reqs.isNotEmpty()) { while (vs.reqs.isNotEmpty()) { delay(5000); now = System.currentTimeMillis() } }
    val live = vs.reqs.filter { it.exp > now + vs.offset }
    if (live.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        live.take(3).forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sky.Chip).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Mic, null, tint = Sky.Blue, modifier = Modifier.size(22.dp))
                Text("${if (r.dn.isNotBlank()) "${r.dn} (${r.from})" else r.from} می‌خواهد وارد ویس «${r.id}» شود", color = Sky.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Check, "قبول", tint = Sky.Ok, modifier = Modifier.size(30.dp).clickable { VoiceService.cmd(c, VoiceService.VOICE, "op" to "dec", "id" to r.id, "n" to r.from, "ok" to "1") })
                Icon(Icons.Rounded.Close, "رد", tint = Sky.Bad, modifier = Modifier.size(30.dp).clickable { VoiceService.cmd(c, VoiceService.VOICE, "op" to "dec", "id" to r.id, "n" to r.from, "ok" to "0") })
            }
        }
    }
}

// ───────────────────────── ساخت ویس‌کال (برای همه‌ی کاربرها) ─────────────────────────
@Composable
fun CreateCallDlg(onDismiss: () -> Unit) {
    val c = LocalContext.current
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(0) }
    var pass by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("ساخت ویس‌کال", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it.take(48) }, "اسم ویس (۲ تا ۳۲ نویسه)", Icons.Rounded.Mic)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "بدون رمز", 1 to "با رمز", 2 to "خصوصی").forEach { (v, l) ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (type == v) Sky.Blue else Sky.Chip).clickable { type = v }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text(l, color = if (type == v) Color.White else Sky.Ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (type == 1) Field(pass, { pass = it.take(16) }, "رمز (حداکثر ۱۶ نویسه)", Icons.Rounded.Lock, password = true)
                if (type == 2) Text("خصوصی: فقط با دعوت تو یا درخواست ورودی که تأیید کنی می‌شود وارد شد.", color = Sky.Ink2, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton({
                if (name.trim().length >= 2 && (type != 1 || pass.isNotBlank())) { VoiceService.cmd(c, VoiceService.VOICE, "op" to "create", "id" to name.trim(), "type" to "$type", "pass" to pass); onDismiss() }
            }) { Text("بساز", color = Sky.Blue, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onDismiss) { Text("انصراف", color = Sky.Ink2) } })
}
