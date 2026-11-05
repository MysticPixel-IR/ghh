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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
// نقش داخل گروه (owner/admin/member) با نقش خود برنامه (owner/admin/member) جدا و قاطی‌نشدنی است.
data class GInfo(val id: String, val name: String, val role: String, val mode: String, val n: Int, val code: String = "", val req: Int = 0)
data class GMember(val n: String, val dn: String, val role: String)
data class GMem(val id: String, val name: String, val mode: String, val mine: Boolean, val rank: Int, val code: String, val reqs: List<Lite>, val bans: List<Lite>, val members: List<GMember>, val bot: Boolean = false)
data class GFound(val id: String, val name: String, val n: Int, val owner: String)
data class GState(val list: List<GInfo> = emptyList(), val mem: GMem? = null, val found: List<GFound> = emptyList(), val peekId: String = "", val peek: List<Msg> = emptyList())

object Grp {
    val state = MutableStateFlow(GState())

    fun name(id: String) = state.value.list.firstOrNull { it.id == id }?.name ?: "گروه"
    fun info(id: String) = state.value.list.firstOrNull { it.id == id }
    fun ids(): List<String> = state.value.list.map { "c:" + it.id }

    private fun JSONArray?.lites(): List<Lite> = if (this == null) emptyList() else (0 until length()).map { val o = getJSONObject(it); Lite(o.optString("n"), o.optString("dn")) }

    fun onList(j: JSONObject) {
        val a = j.optJSONArray("list") ?: JSONArray()
        val l = (0 until a.length()).map { val o = a.getJSONObject(it); GInfo(o.optString("id"), o.optString("name"), o.optString("role", "member"), o.optString("mode", "code"), o.optInt("n"), o.optString("code"), o.optInt("req")) }
        state.update { it.copy(list = l) }
        Chats.retainGroups(l.map { "c:" + it.id }.toSet())
    }

    fun onMem(j: JSONObject) {
        val ma = j.optJSONArray("members") ?: JSONArray()
        val m = GMem(
            j.optString("id"), j.optString("name"), j.optString("mode", "code"), j.optBoolean("mine"), j.optInt("rank", -1), j.optString("code"),
            j.optJSONArray("reqs").lites(), j.optJSONArray("bans").lites(),
            (0 until ma.length()).map { val o = ma.getJSONObject(it); GMember(o.optString("n"), o.optString("dn"), o.optString("role", "member")) },
            j.optBoolean("bot")
        )
        state.update { it.copy(mem = m) }
    }

    fun onGone(j: JSONObject) {
        val id = j.optString("id")
        state.update { s -> s.copy(list = s.list.filterNot { it.id == id }, mem = if (s.mem?.id == id) null else s.mem) }
        Chats.drop("c:$id")
    }

    fun onFound(j: JSONObject) {
        val a = j.optJSONArray("list") ?: JSONArray()
        state.update { it.copy(found = (0 until a.length()).map { i -> val o = a.getJSONObject(i); GFound(o.optString("id"), o.optString("name"), o.optInt("n"), o.optJSONObject("owner")?.optString("n").orEmpty()) }) }
    }

    fun onPeek(j: JSONObject) {
        val a = j.optJSONArray("msgs") ?: JSONArray()
        state.update { it.copy(peekId = j.optString("id"), peek = (0 until a.length()).map { i -> a.getJSONObject(i).toMsg() }) }
    }
}

/** بازبینی حرکت: با «کاهش انیمیشن» سیستم یا حالت باتری کم، انیمیشن‌ها خاموش می‌شوند */
fun motionAllowed(c: Context): Boolean {
    val scale = android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val pm = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    return scale > 0f && pm?.isPowerSaveMode != true
}

private fun gnm(n: String, dn: String) = if (dn.isBlank() || dn.equals(n, true)) n else "$dn ($n)"
private fun roleLabel(r: String) = when (r) { "owner" -> "مالک گروه"; "admin" -> "ادمین گروه"; else -> "عضو" }
private fun modeLabel(m: String) = when (m) { "request" -> "با درخواست"; "closed" -> "بسته"; else -> "با کد" }

// ───────────────────────── دیالوگ‌های ساخت و پیوستن ─────────────────────────
@Composable
fun CreateGroupDlg(onDismiss: () -> Unit) {
    val c = LocalContext.current
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("code") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("گروه جدید", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it.take(60) }, "اسم گروه (۲ تا ۳۰ نویسه)", Icons.Rounded.Groups)
                Text("ورود به گروه", color = Sky.Ink2, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("code" to "با کد دعوت", "request" to "با درخواست").forEach { (v, l) ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (mode == v) Sky.Blue else Sky.Chip).clickable { mode = v }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text(l, color = if (mode == v) Color.White else Sky.Ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (name.trim().length >= 2) { VoiceService.cmd(c, VoiceService.GRP, "op" to "create", "name" to name.trim(), "mode" to mode); onDismiss() } }) { Text("بساز", color = Sky.Blue, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onDismiss) { Text("انصراف", color = Sky.Ink2) } })
}

@Composable
fun JoinGroupDlg(onDismiss: () -> Unit) {
    val c = LocalContext.current
    var code by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("پیوستن با کد", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = { Field(code, { code = it.uppercase().take(16) }, "کد دعوت (۸ حرفی)", Icons.Rounded.Link) },
        confirmButton = { TextButton({ if (code.trim().length >= 4) { VoiceService.cmd(c, VoiceService.GRP, "op" to "join", "code" to code.trim()); onDismiss() } }) { Text("بپیوند", color = Sky.Blue, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onDismiss) { Text("انصراف", color = Sky.Ink2) } })
}

/** مدیر برنامه: جست‌وجوی همه‌ی گروه‌ها */
@Composable
fun StaffGroupsDlg(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = LocalContext.current
    val st by Grp.state.collectAsState()
    var q by remember { mutableStateOf("") }
    LaunchedEffect(q) { delay(350); VoiceService.cmd(c, VoiceService.GRP, "op" to "all", "q" to q.trim()) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("همه‌ی گروه‌ها (مدیر برنامه)", color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(q, { q = it.take(32) }, "اسم گروه", Icons.Rounded.Search)
                st.found.forEach { g ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(g.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, color = Sky.Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${g.n} عضو · مالک: ${g.owner}", color = Sky.Ink2, fontSize = 12.sp)
                        }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("بستن", color = Sky.Ink2) } })
}

// ───────────────────────── صفحه‌ی اطلاعات و مدیریت گروه ─────────────────────────
@Composable
fun GroupInfoScreen(s: Ui, gid: String, onBack: () -> Unit) {
    val c = LocalContext.current
    val clip = LocalClipboardManager.current
    val st by Grp.state.collectAsState()
    val g = st.mem?.takeIf { it.id == gid }
    var target by remember { mutableStateOf<GMember?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf("") }
    val staff = s.role == "owner" || s.role == "admin"
    val soc by Social.state.collectAsState()

    LaunchedEffect(gid) { VoiceService.cmd(c, VoiceService.GRP, "op" to "mem", "id" to gid) }
    LaunchedEffect(g?.name) { if (g != null && rename.isEmpty()) rename = g.name }
    fun act(op: String, vararg extra: Pair<String, String>) = VoiceService.cmd(c, VoiceService.GRP, "op" to op, "id" to gid, *extra)

    if (g == null) { Column(Modifier.fillMaxSize().padding(16.dp)) { Text("در حال بارگذاری…", color = Sky.Ink2); TextButton(onBack) { Text("بازگشت", color = Sky.Blue) } }; return }

    val manage = g.rank >= 1
    val owner = g.rank >= 2

    target?.let { t ->
        AlertDialog(onDismissRequest = { target = null }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text(gnm(t.n, t.dn), color = Sky.Ink, fontWeight = FontWeight.Bold, maxLines = 1) },
            text = {
                Column {
                    if (owner && t.role != "owner") TextButton({ act("adm", "n" to t.n, "on" to if (t.role == "admin") "0" else "1"); target = null }) { Text(if (t.role == "admin") "برداشتن از ادمینی گروه" else "ادمین گروه کردن", color = Sky.Blue) }
                    if (owner && t.role != "owner") TextButton({ act("transfer", "n" to t.n); target = null }) { Text("دادن مالکیت گروه", color = Sky.Warn) }
                    if (t.role != "owner") TextButton({ act("kick", "n" to t.n, "ban" to "0"); target = null }) { Text("بیرون کردن", color = Sky.Bad) }
                    if (t.role != "owner") TextButton({ act("kick", "n" to t.n, "ban" to "1"); target = null }) { Text("بیرون کردن و بن", color = Sky.Bad) }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ target = null }) { Text("بستن", color = Sky.Ink2) } })
    }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("حذف گروه؟", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = { Text("گروه و همه‌ی پیام‌هایش برای همه‌ی اعضا پاک می‌شود و برگشت ندارد.", color = Sky.Ink2, fontSize = 13.sp) },
        confirmButton = { TextButton({ act("del"); confirmDel = false; onBack() }) { Text("حذف کن", color = Sky.Bad, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("انصراف", color = Sky.Ink2) } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
        title = { Text("ترک گروه؟", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = { Text("اگر مالک گروه هستی، اول باید مالکیت را به کسی بدهی یا گروه را حذف کنی.", color = Sky.Ink2, fontSize = 13.sp) },
        confirmButton = { TextButton({ act("leave"); confirmLeave = false; onBack() }) { Text("ترک کن", color = Sky.Bad, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("انصراف", color = Sky.Ink2) } })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ArrowBack, "بازگشت", tint = Sky.Ink, modifier = Modifier.size(28.dp).clickable { onBack() })
            Text(g.name, color = Sky.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!g.mine) Chip(Icons.Rounded.AdminPanelSettings, "دسترسی مدیر برنامه — عضو این گروه نیستی", Sky.Warn)

        if (manage) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("مدیریت", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Field(rename, { rename = it.take(60) }, "اسم گروه", Icons.Rounded.Edit)
                if (rename.trim() != g.name && rename.trim().length >= 2) SkyButton("ذخیره‌ی اسم", Icons.Rounded.CheckCircle, Modifier.fillMaxWidth()) { act("rename", "name" to rename.trim()) }
                Text("ورود به گروه", color = Sky.Ink2, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("code", "request", "closed").forEach { v ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (g.mode == v) Sky.Blue else Sky.Chip).clickable { act("mode", "mode" to v) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                            Text(modeLabel(v), color = if (g.mode == v) Color.White else Sky.Ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("ربات آمار ویس‌کال", color = Sky.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("هر چند دقیقه تعداد بازیکن‌های داخل ویس‌کال‌ها را در این گروه می‌فرستد", color = Sky.Ink2, fontSize = 11.sp)
                    }
                    Switch(checked = g.bot, onCheckedChange = { act("bot", "on" to if (it) "1" else "0") })
                }
                if (g.mode != "closed") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(g.code, color = Sky.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    TextButton({ clip.setText(AnnotatedString(g.code)) }) { Text("کپی", color = Sky.Blue) }
                    TextButton({ act("code") }) { Text("کد جدید", color = Sky.Warn) }
                }
            }
        }

        // دعوت مستقیم دوست: کارت داخل پیوی با دکمه‌ی «بپیوند»؛ بدون کد و بدون صف درخواست
        val invitable = soc.friends.filter { f -> g.members.none { it.n.equals(f.n, true) } }
        if (manage && invitable.isNotEmpty()) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("دعوت دوست (بدون کد)", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                invitable.take(20).forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(f.n, 34.dp, Sky.Blue)
                        Text(gnm(f.n, f.dn), color = Sky.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton({ act("invite", "n" to f.n) }) { Text("دعوت", color = Sky.Blue, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (manage && g.reqs.isNotEmpty()) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("درخواست‌های عضویت (${g.reqs.size})", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                g.reqs.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(p.n, 36.dp, Sky.Blue)
                        Text(gnm(p.n, p.dn), color = Sky.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.Check, "قبول", tint = Sky.Ok, modifier = Modifier.size(30.dp).clickable { act("reqok", "n" to p.n) })
                        Icon(Icons.Rounded.Close, "رد", tint = Sky.Bad, modifier = Modifier.size(30.dp).clickable { act("reqno", "n" to p.n) })
                    }
                }
            }
        }

        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("اعضا (${g.members.size})", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                g.members.sortedWith(compareByDescending<GMember> { it.role == "owner" }.thenByDescending { it.role == "admin" }.thenBy { it.n.lowercase() }).forEach { m ->
                    val can = manage && m.role != "owner" && !m.n.equals(s.name, true)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (can) Modifier.clickable { target = m } else Modifier).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(m.n, 38.dp, Sky.Blue)
                        Column(Modifier.weight(1f)) {
                            Text(gnm(m.n, m.dn), color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.role != "member") Text(roleLabel(m.role), color = if (m.role == "owner") Sky.Warn else Sky.Blue, fontSize = 12.sp)
                        }
                        if (can) Icon(Icons.Rounded.Edit, null, tint = Sky.Ink2, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (manage && g.bans.isNotEmpty()) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("بن‌شده‌ها", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                g.bans.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { Text(gnm(p.n, p.dn), color = Sky.Ink, modifier = Modifier.weight(1f)); TextButton({ act("unban", "n" to p.n) }) { Text("رفع بن", color = Sky.Blue) } } }
            }
        }

        if (!g.mine && staff) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("پیش‌نمایش پیام‌ها (فقط خواندن)", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                TextButton({ act("peek") }) { Text("نمایش ۳۰ پیام آخر", color = Sky.Blue) }
                if (st.peekId == gid) st.peek.forEach { m -> Text("${m.f}: ${if (m.del) "(حذف‌شده)" else m.x}", color = Sky.Ink2, fontSize = 12.sp) }
            }
        }

        if (g.mine) SkyButton("ترک گروه", Icons.Rounded.Close, Modifier.fillMaxWidth()) { confirmLeave = true }
        if (owner) SkyButton("حذف گروه", Icons.Rounded.Delete, Modifier.fillMaxWidth()) { confirmDel = true }
        Spacer(Modifier.height(8.dp))
    }
}

// ───────────────────────── کارت دعوت به گروه (بدون کد) ─────────────────────────
@Composable
fun GroupInviteCard(s: Ui, m: Msg, onTap: () -> Unit) {
    val c = LocalContext.current
    val gst by Grp.state.collectAsState()
    val vs by VoiceX.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val sNow = now + vs.offset
    val member = gst.list.any { it.id == m.vid }
    val expired = m.ve > 0 && sNow > m.ve
    val violet = Color(0xFFA48CFF)
    Column(
        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(violet.copy(alpha = 0.12f)).clickable { onTap() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(21.dp)).background(violet), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Groups, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            Column(Modifier.weight(1f)) {
                Text(m.vby, color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("دعوت به گروه از طرف ${m.f}", color = Sky.Ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (m.ve > 0 && !expired && !member) Text("${((m.ve - sNow) / 3_600_000L).coerceAtLeast(0)} ساعت دیگر معتبر است", color = Sky.Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val enabled = !member && !expired && s.state == St.Connected
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (enabled) violet else Color(0xFFD5DCE6))
                .clickable(enabled = enabled) { VoiceService.cmd(c, VoiceService.GRP, "op" to "accept", "id" to m.vid) }.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (member) "عضو این گروه هستی" else if (expired) "دعوت منقضی شد" else "بپیوند", color = if (enabled) Color.White else Sky.Ink2, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
    }
}
