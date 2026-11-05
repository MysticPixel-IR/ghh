package app.minevoice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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

// ───────────────────────── مدل (دوستی، بلاک، حریم خصوصی) ─────────────────────────
data class Lite(val n: String, val dn: String)
/** s: 0 آفلاین (یا مخفی)، 1 آنلاین، 2 داخل بازی، 3 در حال صحبت */
data class Friend(val n: String, val dn: String, val s: Int)
data class PrivS(val dm: String = "all", val online: String = "all", val bio: String = "all")
data class SocState(
    val friends: List<Friend> = emptyList(), val inc: List<Lite> = emptyList(), val out: List<Lite> = emptyList(),
    val blocked: List<Lite> = emptyList(), val priv: PrivS = PrivS()
)

object Social {
    val state = MutableStateFlow(SocState())

    private fun JSONArray?.lites(): List<Lite> = if (this == null) emptyList() else (0 until length()).map { val o = getJSONObject(it); Lite(o.optString("n"), o.optString("dn")) }

    fun onState(j: JSONObject) {
        val fa = j.optJSONArray("friends")
        val friends = if (fa == null) emptyList() else (0 until fa.length()).map { val o = fa.getJSONObject(it); Friend(o.optString("n"), o.optString("dn"), o.optInt("s")) }
        val p = j.optJSONObject("priv")
        state.value = SocState(
            friends.sortedWith(compareByDescending<Friend> { it.s }.thenBy { it.n.lowercase() }),
            j.optJSONArray("inc").lites(), j.optJSONArray("out").lites(), j.optJSONArray("blocked").lites(),
            PrivS(p?.optString("dm", "all") ?: "all", p?.optString("online", "all") ?: "all", p?.optString("bio", "all") ?: "all")
        )
    }

    /** وضعیت دسته‌ای دوستان (هر ۱ ثانیه فقط اگر تغییری بود) */
    fun onStatus(j: JSONObject) {
        val m = j.optJSONObject("s") ?: return
        state.update { st -> st.copy(friends = st.friends.map { f -> val k = f.n.lowercase(); if (m.has(k)) f.copy(s = m.optInt(k)) else f }) }
    }

    fun blockedKeys(): Set<String> = state.value.blocked.map { it.n.lowercase() }.toSet()
    fun isFriend(n: String) = state.value.friends.any { it.n.equals(n, true) }
    fun isPending(n: String) = state.value.out.any { it.n.equals(n, true) }
}

private fun nm(n: String, dn: String) = if (dn.isBlank() || dn.equals(n, true)) n else "$dn ($n)"
private fun statusText(s: Int) = when (s) { 3 -> "در حال صحبت"; 2 -> "داخل بازی"; 1 -> "آنلاین"; else -> "آفلاین" }
private fun statusColor(s: Int) = when (s) { 3, 1 -> Sky.Ok; 2 -> Sky.Blue; else -> Sky.Ink2 }

/** کارت سه‌گزینه‌ای حریم خصوصی؛ در مرحله‌ی تنظیمات همین کامپوننت داخل کارت «حریم خصوصی» هم استفاده می‌شود */
@Composable
fun PrivacyCard(p: PrivS, onPick: (field: String, value: String) -> Unit) {
    Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
        Text("حریم خصوصی", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        PrivacyBody(p, onPick)
    }
}

/** محتوای حریم خصوصی بدون کارت؛ هم داخل صفحه‌ی دوستان و هم داخل کارت جمع‌شونده‌ی تنظیمات */
@Composable
fun PrivacyBody(p: PrivS, onPick: (field: String, value: String) -> Unit) {
    @Composable
    fun row(title: String, field: String, cur: String) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Sky.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "همه", "friends" to "دوستان", "none" to "هیچ‌کس").forEach { (v, l) ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (cur == v) Sky.Blue else Sky.Chip).clickable { onPick(field, v) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(l, color = if (cur == v) Color.White else Sky.Ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    run {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            row("چه کسی می‌تواند پیوی بدهد", "dm", p.dm)
            Text("غریبه‌ها تا وقتی قبول نکنی فقط ۳ پیام می‌توانند بفرستند و در «درخواست پیام» می‌مانند.", color = Sky.Ink2, fontSize = 11.sp)
            row("چه کسی آنلاین بودنم را ببیند", "online", p.online)
            row("چه کسی بیوی من را ببیند", "bio", p.bio)
        }
    }
}

// ───────────────────────── صفحه‌ی دوستان ─────────────────────────
@Composable
fun FriendsScreen(s: Ui, onBack: () -> Unit) {
    val c = LocalContext.current
    val soc by Social.state.collectAsState()
    val chats by Chats.state.collectAsState()
    var q by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<Friend?>(null) }
    var confirmBlock by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { VoiceService.cmd(c, VoiceService.SOC, "op" to "list") }
    LaunchedEffect(q) { if (q.trim().length >= 2) { delay(450); VoiceService.cmd(c, VoiceService.CHAT, "op" to "find", "q" to q.trim()) } }
    fun act(op: String, name: String) = VoiceService.cmd(c, VoiceService.SOC, "op" to op, "name" to name)

    menu?.let { f ->
        AlertDialog(onDismissRequest = { menu = null }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text(nm(f.n, f.dn), color = Sky.Ink, fontWeight = FontWeight.Bold, maxLines = 1) },
            text = {
                Column {
                    TextButton({ val id = Chats.dmId(s.name, f.n); Chats.ensure(id); menu = null; onBack(); Chats.open.value = id }) { Text("ارسال پیام", color = Sky.Blue) }
                    TextButton({ act("rm", f.n); menu = null }) { Text("حذف از دوستان", color = Sky.Bad) }
                    TextButton({ confirmBlock = f.n; menu = null }) { Text("بلاک", color = Sky.Bad) }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ menu = null }) { Text("بستن", color = Sky.Ink2) } })
    }
    confirmBlock?.let { n ->
        AlertDialog(onDismissRequest = { confirmBlock = null }, containerColor = Sky.Card, shape = RoundedCornerShape(24.dp),
            title = { Text("بلاک $n؟", color = Sky.Ink, fontWeight = FontWeight.Bold) },
            text = { Text("دوستی و درخواست‌ها پاک می‌شود، پیوی هر دو طرف بسته می‌شود و پیام‌های او را در چت جهانی نمی‌بینی. هر وقت خواستی رفع بلاک می‌کنی.", color = Sky.Ink2, fontSize = 13.sp) },
            confirmButton = { TextButton({ act("blk", n); Chats.hide(Chats.dmId(s.name, n)); confirmBlock = null }) { Text("بلاک کن", color = Sky.Bad, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ confirmBlock = null }) { Text("انصراف", color = Sky.Ink2) } })
    }

    @Composable
    fun person(n: String, dn: String, sub: String? = null, subColor: Color = Sky.Ink2, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(n, 40.dp, Sky.Blue)
            Column(Modifier.weight(1f)) {
                Text(nm(n, dn), color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, color = subColor, fontSize = 12.sp)
            }
            trailing()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ArrowBack, "بازگشت", tint = Sky.Ink, modifier = Modifier.size(28.dp).clickable { onBack() })
            Text("دوستان", color = Sky.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        if (s.state != St.Connected) Chip(Icons.Rounded.Warning, "برای تغییر دوستان باید به سرور وصل باشی", Sky.Warn)

        if (soc.inc.isNotEmpty()) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("درخواست‌های دریافتی (${soc.inc.size})", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                soc.inc.forEach { p ->
                    person(p.n, p.dn) {
                        Icon(Icons.Rounded.Check, "قبول", tint = Sky.Ok, modifier = Modifier.size(30.dp).clickable { act("acc", p.n) })
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.Close, "رد", tint = Sky.Bad, modifier = Modifier.size(30.dp).clickable { act("dec", p.n) })
                    }
                }
            }
        }

        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("دوستان (${soc.friends.size})", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                if (soc.friends.isEmpty()) Text("هنوز دوستی نداری. پایین‌تر بازیکن جست‌وجو کن و «افزودن» را بزن.", color = Sky.Ink2, fontSize = 12.sp)
                soc.friends.forEach { f ->
                    person(f.n, f.dn, statusText(f.s), statusColor(f.s), onClick = { menu = f }) {
                        if (f.s == 3) Icon(Icons.Rounded.Mic, null, tint = Sky.Ok, modifier = Modifier.size(20.dp))
                        else Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(f.s)))
                    }
                }
            }
        }

        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("افزودن دوست", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Field(q, { q = it.take(32) }, "اسم بازیکن (حداقل ۲ حرف)", Icons.Rounded.Person)
                if (q.trim().length >= 2) chats.found.filter { !it.n.equals(s.name, true) && !Social.isFriend(it.n) }.take(8).forEach { p ->
                    person(p.n, p.dn) {
                        if (Social.isPending(p.n)) Text("فرستاده شد", color = Sky.Ink2, fontSize = 12.sp)
                        else TextButton({ act("req", p.n) }, enabled = s.state == St.Connected) { Text("افزودن", color = Sky.Blue, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (soc.out.isNotEmpty()) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("درخواست‌های ارسالی", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                soc.out.forEach { p -> person(p.n, p.dn) { TextButton({ act("can", p.n) }) { Text("لغو", color = Sky.Bad) } } }
            }
        }

        PrivacyCard(soc.priv) { field, value -> VoiceService.cmd(c, VoiceService.SOC, "op" to "priv", field to value) }

        if (soc.blocked.isNotEmpty()) Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column {
                Text("بلاک‌شده‌ها", color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                soc.blocked.forEach { p -> person(p.n, p.dn) { TextButton({ act("unblk", p.n) }) { Text("رفع بلاک", color = Sky.Blue) } } }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
