package app.minevoice

import android.Manifest
import android.os.Build
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.key
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.update

private fun adm(c: Context, op: String, vararg e: Pair<String, String>) = VoiceService.cmd(c, VoiceService.ADM, "op" to op, *e)

@Composable
private fun Empty(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Sky.Ink.copy(alpha = 0.55f), modifier = Modifier.size(44.dp))
        Text(text, color = Sky.Ink, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

// ───────────────────────── اتصال ─────────────────────────
private fun ago(t: Long): String {
    val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
    return when { s < 60 -> "چند ثانیه پیش"; s < 3600 -> "${s / 60} دقیقه پیش"; s < 86400 -> "${s / 3600} ساعت پیش"; else -> "${s / 86400} روز پیش" }
}

private fun fmtDur(ms: Long): String {
    val m = ms / 60000
    return if (m < 1) "${ms / 1000} ثانیه" else if (m >= 60) "${m / 60} ساعت و ${m % 60} دقیقه" else "$m دقیقه"
}

@Composable
private fun TopCard(s: Ui) {
    val c = LocalContext.current
    LaunchedEffect(Unit) { while (true) { VoiceService.cmd(c, VoiceService.TOP); delay(30000) } }
    Glass(Modifier.fillMaxWidth()) {
        Text("🏆 برترین‌ها (مدت صحبت)", color = Sky.Ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("مدت صحبت تو: ${fmtDur(s.myTalkMs)}" + (if (s.myRank > 0) " · رتبه‌ی ${s.myRank}" else ""),
            color = Sky.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        s.top.forEachIndexed { i, e ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${i + 1}", color = Sky.Warn, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.width(18.dp))
                Avatar(e.n, 30.dp)
                Text(e.n, color = Sky.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(fmtDur(e.ms), color = Sky.Ink2, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BackgroundCard() {
    val c = LocalContext.current
    fun open(i: Intent) { runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    Glass(Modifier.fillMaxWidth(), pad = 16.dp, borderColor = Sky.Bad.copy(alpha = 0.5f)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("تا ویس وسط بازی قطع نشود", color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("بعضی گوشی‌ها برنامه‌ی پس‌زمینه را می‌بندند. اجازه‌ی «بدون محدودیت باتری» را بده. در شیائومی، سامسونگ، آنر و مشابه آن‌ها داخل تنظیمات برنامه «شروع خودکار» را هم روشن کن و باتری را روی «بدون محدودیت» بگذار.",
                color = Sky.Ink2, fontSize = 12.sp)
            SkyButton("اجازه‌ی کار در پس‌زمینه", Icons.Rounded.BatteryChargingFull, Modifier.fillMaxWidth()) {
                runCatching {
                    c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            SkyButton("باز کردن تنظیمات برنامه", Icons.Rounded.Settings, Modifier.fillMaxWidth()) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
            }
        }
    }
}

@Composable
fun ConnectScreen(s: Ui) {
    val c = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) VoiceService.cmd(c, VoiceService.START, "code" to code)
        else VoiceService.ui.update { it.copy(msg = "برای ویس باید اجازه‌ی میکروفون را بدهید.") }
    }
    val inf = rememberInfiniteTransition(label = "logo")
    val fy by inf.animateFloat(-6f, 6f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fy")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.offset(y = fy.dp)) { PulseLoader(Icons.Rounded.RecordVoiceOver, 170.dp) }
        Text("رویال ویس", color = Sky.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("ویس چت نزدیکی برای سرور ماینکرفت", color = Sky.Ink, fontSize = 13.sp)
        if (!s.bgOk) BackgroundCard()
        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(Icons.Rounded.Link, "اتصال در سه مرحله")
                Text("۱) وارد سرور ماینکرفت شو و بنویس:  /voice link", color = Sky.Ink, fontSize = 13.sp)
                Text("۲) کد ۶ رقمی که گرفتی را همین‌جا بنویس. کد ۵ دقیقه اعتبار دارد.", color = Sky.Ink, fontSize = 13.sp)
                Text("۳) روی «اتصال» بزن؛ اسم خودت و وضعیت آنلاین‌بودنت خودکار نشان داده می‌شود.", color = Sky.Ink, fontSize = 13.sp)
                Field(code, { code = it.filter(Char::isDigit).take(6) }, "کد ۶ رقمی", Icons.Rounded.Link, keyboard = KeyboardType.Number)
                if (s.msg.isNotEmpty()) Chip(Icons.Rounded.Warning, s.msg, Sky.Bad)
                SkyButton("اتصال", Icons.Rounded.Bolt, Modifier.fillMaxWidth()) {
                    perm.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        }
    }
}

@Composable
fun ConnectingScreen(s: Ui) {
    val c = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        PulseLoader(Icons.Rounded.RecordVoiceOver, 190.dp)
        Spacer(Modifier.height(18.dp))
        Text(if (s.state == St.Reconnecting) "اتصال قطع شد، دوباره وصل می‌شویم…" else "در حال اتصال…", color = Sky.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (s.msg.isNotEmpty()) Text(s.msg, color = Sky.Ink, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        SkyButton("لغو", Icons.Rounded.Close, danger = true) { VoiceService.cmd(c, VoiceService.STOP) }
    }
}

// ───────────────────────── کیفیت اتصال ─────────────────────────
/** سه میله + نوع مسیر + ping: سبز زیر ۹۰ms، زرد زیر ۲۲۰ms، قرمز بیشتر */
@Composable
private fun QualityChip(s: Ui) {
    val ms = s.ping
    val lvl = when { ms < 0 -> 0; ms < 90 -> 3; ms < 220 -> 2; else -> 1 }
    val col = when (lvl) { 3 -> Sky.Ok; 2 -> Sky.Warn; 1 -> Sky.Bad; else -> Sky.Ink2 }
    Row(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(16.dp, 14.dp)) {
            val w = size.width / 5f
            for (i in 0 until 3) {
                val h = size.height * (0.4f + 0.3f * i)
                drawRoundRect(if (i < lvl) col else col.copy(alpha = 0.25f), Offset(i * 2 * w, size.height - h), Size(w * 1.4f, h), CornerRadius(2f))
            }
        }
        Text((if (s.udp) "UDP" else "TCP") + (if (ms >= 0) " · ${ms}ms" else ""), color = col, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ───────────────────────── صدا (خانه) ─────────────────────────
@Composable
private fun MicOrb(s: Ui) {
    val c = LocalContext.current
    val live = s.inGame || s.group.isNotEmpty()
    val target = when {
        s.staffMuted || s.deaf || (s.muted && !s.ptt) -> Sky.Bad
        !live -> Color(0xFF8EA7BE)
        s.talking || s.pttDown -> Sky.Ok
        else -> Sky.Blue
    }
    val icon = when {
        s.staffMuted -> Icons.Rounded.Block
        s.muted && !s.ptt -> Icons.Rounded.MicOff
        s.ptt && !s.pttDown -> Icons.Rounded.TouchApp
        else -> Icons.Rounded.Mic
    }
    val col by animateColorAsState(target, tween(350), label = "oc")
    val inf = rememberInfiniteTransition(label = "orb")
    val ring by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val breathe by inf.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br")
    val speaking = s.talking || s.pttDown
    val haptic = LocalHapticFeedback.current
    val lv by animateFloatAsState(if (speaking) s.level.coerceIn(0f, 1f) else 0f, tween(90), label = "lv")
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.34f
            drawCircle(col.copy(alpha = 0.20f), base * (1.1f + 0.55f * lv)) // با بلندی صدای تو بزرگ و کوچک می‌شود
            if (speaking) for (k in 0..1) {
                val q = (ring + k * 0.5f) % 1f
                drawCircle(col.copy(alpha = 0.45f * (1 - q)), base + q * size.minDimension * 0.15f, style = Stroke(width = 4.dp.toPx()))
            }
            drawCircle(col.copy(alpha = 0.16f), base * 1.2f * breathe)
        }
        Box(Modifier.size(136.dp).shadow(16.dp, CircleShape, spotColor = col, ambientColor = col).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(lerp(col, Color.White, 0.28f), col)))
            .pointerInput(s.ptt) {
                detectTapGestures(
                    onPress = {
                        if (s.ptt) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            VoiceService.cmd(c, VoiceService.PTT, "d" to "1")
                            tryAwaitRelease()
                            VoiceService.cmd(c, VoiceService.PTT, "d" to "0")
                        }
                    },
                    onTap = { if (!s.ptt) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); VoiceService.cmd(c, VoiceService.MUTE) } })
            }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(62.dp))
        }
    }
}

@Composable
private fun GroupCard(s: Ui) {
    // زمانی که در گروه بوده‌ای (هر ثانیه به‌روز می‌شود)
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val sec = if (s.groupSince > 0) ((now - s.groupSince) / 1000).coerceAtLeast(0) else 0L
    val t = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    Glass(Modifier.fillMaxWidth()) {
        Text("👥 ${s.group} · ${s.members.size} نفر · ⏱ $t", color = Sky.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            s.members.forEach { n ->
                val talking = n in s.speaking
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.border(2.dp, if (talking) Sky.Ok else Color.Transparent, CircleShape).padding(2.dp)) { Avatar(n, 44.dp, if (talking) Sky.Ok else Sky.Blue) }
                    Text(n, color = Sky.Ink2, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(s: Ui, onUpdate: () -> Unit) {
    val c = LocalContext.current
    val live = s.inGame || s.group.isNotEmpty()
    val hint = when {
        s.staffMuted -> "ادمین میکروفون شما را بسته است"
        s.deaf -> "صدا بسته است — نه می‌شنوی، نه شنیده می‌شوی"
        !live -> "غیرفعال — وارد سرور شو یا به یک گروه برو"
        s.ptt -> if (s.pttDown) "در حال ارسال…" else "برای صحبت نگه دار"
        s.muted -> "میکروفون خاموش است — لمس کن"
        s.talking -> "در حال صحبت…"
        else -> "میکروفون روشن است"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(s.name, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Sky.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                PresenceChip(s.inGame)
            }
            QualityChip(s)
        }
        s.update?.let { u ->
            Glass(Modifier.fillMaxWidth(), pad = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Download, null, tint = Sky.Blue, modifier = Modifier.size(26.dp))
                    Text("نسخه‌ی جدید ${u.name} آماده است", color = Sky.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    SkyButton("آپدیت", null, onClick = onUpdate)
                }
            }
        }
        MicOrb(s)
        Text(hint, color = Sky.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (s.msg.isNotEmpty()) Chip(Icons.Rounded.Warning, s.msg, Sky.Bad)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(if (s.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.Hearing, Sky.Blue, Color.White, 50.dp) { VoiceService.cmd(c, VoiceService.SPK) }
            if (!s.ptt) IconBtn(if (s.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (s.muted) Sky.Bad else Sky.Blue, Color.White, 50.dp) { VoiceService.cmd(c, VoiceService.MUTE) }
            IconBtn(if (s.deaf) Icons.Rounded.HeadsetOff else Icons.Rounded.Headset, if (s.deaf) Sky.Bad else Sky.Blue, Color.White, 50.dp) { VoiceService.cmd(c, VoiceService.DEAF) }
            IconBtn(Icons.Rounded.PowerSettingsNew, Sky.Bad, Color.White, 50.dp) { VoiceService.cmd(c, VoiceService.STOP) }
        }
        Glass(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(Icons.Rounded.SportsEsports, if (s.inGame) "در سرور" else "بیرون از سرور", if (s.inGame) Sky.Ok else Sky.Bad)
                if (s.group.isNotEmpty()) Chip(Icons.Rounded.Groups, "گروه ${s.group}", Sky.Blue)
                if (s.admin) Chip(Icons.Rounded.Shield, "ادمین", Sky.Warn)
            }
        }
        if (s.group.isNotEmpty()) GroupCard(s)
        Glass(Modifier.fillMaxWidth().animateContentSize()) {
            Text("در حال صحبت", color = Sky.Ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (s.speaking.isEmpty()) Text("کسی صحبت نمی‌کند", color = Sky.Ink2.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            s.speaking.forEach { n ->
                key(n) {
                    val vs = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(vs, enter = fadeIn(tween(220)) + expandVertically(tween(240))) {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(n, 32.dp, Sky.Ok); Text(n, color = Sky.Ink, fontSize = 15.sp, modifier = Modifier.weight(1f)); Equalizer()
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── گروه‌ها ─────────────────────────
@Composable
fun CreateGroupDialog(onDismiss: () -> Unit) {
    val c = LocalContext.current
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        title = { Text("ساخت گروه", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it.take(32) }, "اسم گروه", Icons.Rounded.Groups)
                Field(pass, { pass = it.take(16) }, "رمز (اختیاری)", Icons.Rounded.Lock)
            }
        },
        confirmButton = {
            TextButton({ if (name.isNotBlank()) { adm(c, "creategroup", "id" to name.trim(), "pass" to pass); onDismiss() } }) {
                Text("بساز", color = Sky.Blue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("انصراف", color = Sky.Ink2) } })
}

@Composable
private fun PassDialog(id: String, onDismiss: () -> Unit) {
    val c = LocalContext.current
    var pass by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        title = { Text("رمز گروه «$id»", color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = { Field(pass, { pass = it }, "رمز", Icons.Rounded.Lock, password = true) },
        confirmButton = {
            TextButton({ VoiceService.cmd(c, VoiceService.GROUP, "id" to id, "pass" to pass); onDismiss() }) {
                Text("ورود", color = Sky.Blue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("انصراف", color = Sky.Ink2) } })
}

@Composable
fun GroupsScreen(s: Ui) {
    val c = LocalContext.current
    var create by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    if (create) CreateGroupDialog { create = false }
    ask?.let { PassDialog(it) { ask = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("گروه‌ها", color = Sky.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (s.admin) SkyButton("ساخت گروه", Icons.Rounded.Add) { create = true }
        }
        if (s.openGroups) Glass(Modifier.fillMaxWidth(), pad = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(typed, { typed = it }, "ورود با اسم گروه", Icons.Rounded.Search, Modifier.weight(1f))
                IconBtn(Icons.Rounded.Check, Color.White, Sky.Blue, 46.dp) { if (typed.isNotBlank()) VoiceService.cmd(c, VoiceService.GROUP, "id" to typed.trim()) }
            }
        }
        if (s.groups.isEmpty()) Empty(Icons.Rounded.Groups, if (s.admin) "هنوز گروهی نیست؛ با «ساخت گروه» یکی بساز." else "هنوز گروهی نیست. ادمین‌ها گروه می‌سازند.")
        s.groups.forEach { g ->
            val mine = g.id == s.group
            val canJoin = !g.closed || s.admin
            val border = if (mine) Sky.Blue else Color.White.copy(alpha = 0.9f)
            Glass(Modifier.fillMaxWidth(), pad = 12.dp, borderColor = border) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(if (mine) Sky.Blue else Sky.Light), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Groups, null, tint = if (mine) Color.White else Sky.Blue, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(g.id, color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${g.n} نفر", color = Sky.Ink2, fontSize = 12.sp)
                            if (g.lock) Icon(Icons.Rounded.Lock, null, tint = Sky.Ink2, modifier = Modifier.size(14.dp))
                            if (g.closed) Chip(Icons.Rounded.Block, "بسته", Sky.Bad)
                        }
                    }
                    if (mine) SkyButton("خروج", Icons.Rounded.Close, danger = true) { VoiceService.cmd(c, VoiceService.GROUP, "id" to "") }
                    else SkyButton("ورود", Icons.Rounded.Check, enabled = canJoin) {
                        if (g.lock && !s.admin) ask = g.id else VoiceService.cmd(c, VoiceService.GROUP, "id" to g.id)
                    }
                }
            }
        }
    }
}

// ───────────────────────── مدیریت (فقط ادمین) ─────────────────────────
@Composable
private fun MuteDialog(title: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = Sky.Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5 to "۵ دقیقه", 30 to "۳۰ دقیقه", 120 to "۲ ساعت", 0 to "تا اطلاع بعدی").forEach { (m, l) ->
                    SkyButton(l, Icons.Rounded.MicOff, Modifier.fillMaxWidth()) { onPick(m) }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("بستن", color = Sky.Ink2) } })
}

@Composable
private fun Confirm(text: String, onYes: () -> Unit, onNo: () -> Unit) =
    AlertDialog(onDismissRequest = onNo, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        text = { Text(text, color = Sky.Ink, fontSize = 15.sp) },
        confirmButton = { TextButton(onYes) { Text("بله", color = Sky.Bad, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onNo) { Text("انصراف", color = Sky.Ink2) } })

@Composable
fun AdminScreen(s: Ui) {
    val c = LocalContext.current
    var sub by rememberSaveable { mutableStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var muteUser by remember { mutableStateOf<String?>(null) }
    var muteGroup by remember { mutableStateOf<String?>(null) }
    var banUser by remember { mutableStateOf<String?>(null) }
    var delGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { adm(c, "list"); delay(2000) } }
    if (create) CreateGroupDialog { create = false }
    muteUser?.let { n -> MuteDialog("بستن میکروفون $n", { muteUser = null }) { m -> adm(c, "mute", "name" to n, "min" to m.toString()); muteUser = null } }
    muteGroup?.let { g -> MuteDialog("بستن میکروفون گروه $g", { muteGroup = null }) { m -> adm(c, "mutegroup", "id" to g, "min" to m.toString()); muteGroup = null } }
    banUser?.let { n -> Confirm("$n از ویس محروم شود؟", { adm(c, "ban", "name" to n); banUser = null }, { banUser = null }) }
    delGroup?.let { g -> Confirm("گروه «$g» حذف شود؟", { adm(c, "deletegroup", "id" to g); delGroup = null }, { delGroup = null }) }

    val a = s.adm
    val titles = listOf("بازیکن‌ها" to Icons.Rounded.Person, "گروه‌ها" to Icons.Rounded.Groups, "محروم‌ها" to Icons.Rounded.Block, "لاگ" to Icons.Rounded.History)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AdminPanelSettings, null, tint = Sky.Warn, modifier = Modifier.size(28.dp))
            Text("مدیریت", color = Sky.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (sub == 1) SkyButton("ساخت گروه", Icons.Rounded.Add) { create = true }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.6f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            titles.forEachIndexed { i, (t, ic) ->
                val sel = i == sub
                val bg by animateColorAsState(if (sel) Sky.Blue else Color.Transparent, label = "sb")
                val fg by animateColorAsState(if (sel) Color.White else Sky.Ink2, label = "sf")
                Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).clickable { sub = i }.padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, tint = fg, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp))
                    Text(t, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        when (sub) {
            0 -> {
                if (a.users.isEmpty()) Empty(Icons.Rounded.Person, "کسی به ویس وصل نیست")
                a.users.forEach { u ->
                    Glass(Modifier.fillMaxWidth(), pad = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(u.name, 40.dp, if (u.admin) Sky.Warn else Sky.Blue)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(u.name, color = Sky.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    if (u.admin) Icon(Icons.Rounded.Shield, null, tint = Sky.Warn, modifier = Modifier.size(15.dp))
                                    if (u.muted) Icon(Icons.Rounded.MicOff, null, tint = Sky.Bad, modifier = Modifier.size(15.dp))
                                }
                                Text((if (u.inGame) "در سرور" else "بیرون") + (if (u.group.isNotEmpty()) " • ${u.group}" else "") + " • " + (if (u.udp) "UDP" else "TCP"),
                                    color = Sky.Ink2, fontSize = 11.sp)
                            }
                            if (!u.admin) {
                                if (u.muted) IconBtn(Icons.Rounded.Mic, Sky.Ok, Sky.Ok.copy(alpha = 0.14f), 38.dp) { adm(c, "unmute", "name" to u.name) }
                                else IconBtn(Icons.Rounded.MicOff, Sky.Warn, Sky.Warn.copy(alpha = 0.14f), 38.dp) { muteUser = u.name }
                                if (u.group.isNotEmpty()) IconBtn(Icons.Rounded.PersonRemove, Sky.Blue, Sky.Light, 38.dp) { adm(c, "kickgroup", "name" to u.name) }
                                IconBtn(Icons.Rounded.Block, Sky.Bad, Sky.Bad.copy(alpha = 0.12f), 38.dp) { banUser = u.name }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (a.groups.isEmpty()) Empty(Icons.Rounded.Groups, "گروهی نیست")
                a.groups.forEach { g ->
                    Glass(Modifier.fillMaxWidth(), pad = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Groups, null, tint = Sky.Blue, modifier = Modifier.size(24.dp))
                            Text(g.id, color = Sky.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (g.lock) Icon(Icons.Rounded.Lock, null, tint = Sky.Ink2, modifier = Modifier.size(15.dp))
                            if (g.closed) Chip(Icons.Rounded.Block, "بسته", Sky.Bad)
                            Chip(Icons.Rounded.Person, "${g.members.size}", Sky.Blue)
                        }
                        if (g.members.isNotEmpty()) Text(g.members.joinToString(" ، "), color = Sky.Ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconBtn(Icons.Rounded.MicOff, Sky.Warn, Sky.Warn.copy(alpha = 0.14f), 40.dp) { muteGroup = g.id }
                            IconBtn(Icons.Rounded.Mic, Sky.Ok, Sky.Ok.copy(alpha = 0.14f), 40.dp) { adm(c, "unmutegroup", "id" to g.id) }
                            IconBtn(if (g.closed) Icons.Rounded.LockOpen else Icons.Rounded.Lock, Sky.Blue, Sky.Light, 40.dp) {
                                adm(c, if (g.closed) "unblockgroup" else "closegroup", "id" to g.id)
                            }
                            IconBtn(Icons.Rounded.Delete, Sky.Bad, Sky.Bad.copy(alpha = 0.12f), 40.dp) { delGroup = g.id }
                        }
                    }
                }
            }
            2 -> {
                if (a.bans.isEmpty()) Empty(Icons.Rounded.Check, "کسی محروم نیست")
                a.bans.forEach { n ->
                    Glass(Modifier.fillMaxWidth(), pad = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(n, 38.dp, Sky.Bad)
                            Text(n, color = Sky.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            SkyButton("رفع محرومیت", Icons.Rounded.Check) { adm(c, "unban", "name" to n) }
                        }
                    }
                }
            }
            else -> {
                if (a.log.isEmpty()) Empty(Icons.Rounded.History, "هنوز چیزی ثبت نشده")
                a.log.forEach { e ->
                    Glass(Modifier.fillMaxWidth(), pad = 10.dp) {
                        Text(e.s, color = Sky.Ink, fontSize = 13.sp)
                        Text(ago(e.t), color = Sky.Ink2, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun LevelMeter(level: Float, thrLevel: Float) {
    val over = level > thrLevel
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Sky.Light)) {
        Box(Modifier.fillMaxWidth(level.coerceIn(0.02f, 1f)).fillMaxHeight().background(if (over) Sky.Ok else Sky.Blue.copy(alpha = 0.55f)))
        Box(Modifier.fillMaxWidth(thrLevel.coerceIn(0.02f, 1f)).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Sky.Warn))
        }
    }
}

// ───────────────────────── تنظیمات ─────────────────────────
@Composable
private fun SettingRow(icon: ImageVector, title: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Sky.Light), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Sky.Blue, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f), color = Sky.Ink, fontSize = 14.sp)
        trailing()
    }
}

@Composable
private fun SwitchSetting(icon: ImageVector, title: String, on: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(icon, title) {
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Sky.Blue, checkedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFFD3E6F5), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
    }

@Composable
private fun SliderSetting(icon: ImageVector, title: String, value: Float, range: ClosedFloatingPointRange<Float>, key: String, pct: Boolean = false) {
    val c = LocalContext.current
    var v by remember(value) { mutableStateOf(value) }
    SettingRow(icon, title) { Text(if (pct) "${(v * 100).toInt()}٪" else "%.1f".format(v), color = Sky.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    Slider(v, { v = it }, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = Sky.Blue, activeTrackColor = Sky.Blue, inactiveTrackColor = Color(0xFFCFE6F7)),
        onValueChangeFinished = { VoiceService.cmd(c, VoiceService.SET, "k" to key, "v" to v.toString()) })
}

@Composable
private fun PlayerVolume(name: String, value: Float) {
    val c = LocalContext.current
    var v by remember(value) { mutableStateOf(value) }
    SettingRow(Icons.Rounded.Person, name) { Text(if (v < 0.05f) "بی‌صدا" else "%.1f".format(v), color = Sky.Ink2, fontSize = 13.sp) }
    Slider(v, { v = it }, valueRange = 0f..2f,
        colors = SliderDefaults.colors(thumbColor = Sky.Blue, activeTrackColor = Sky.Blue, inactiveTrackColor = Color(0xFFCFE6F7)),
        onValueChangeFinished = { VoiceService.cmd(c, VoiceService.SET, "k" to "vol", "v" to name + "\t" + v.toString()) })
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF5CC3FF), Sky.Blue2))),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(title, color = Sky.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            if (hint != null) Text(hint, color = Sky.Ink2, fontSize = 11.sp)
        }
    }
}

/** کارت پروفایل: اسم، سر بازیکن، و حضور داخل سرور که با پلاگین تشخیص داده می‌شود */
@Composable
private fun ProfileCard(s: Ui) {
    Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(s.name.ifEmpty { "?" }, 56.dp, if (s.inGame) Sky.Ok else Sky.Blue)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.name.ifEmpty { "متصل نشده" }, color = Sky.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                if (s.state == St.Connected) {
                    PresenceChip(s.inGame)
                    Chip(Icons.Rounded.Speed, (if (s.ping >= 0) "${s.ping}ms" else "—") + (if (s.udp) " · UDP" else " · TCP"), Sky.Blue)
                } else Chip(Icons.Rounded.Warning, "به ویس وصل نیستی", Sky.Warn)
            }
        }
    }
}

/** ماندن در پس‌زمینه: همیشه دیده می‌شود (حتی بعد از دادن اجازه‌ی باتری) تا «شروع خودکار» و بقیه هم پیدا شوند */
@Composable
private fun BackgroundSection(s: Ui) {
    val c = LocalContext.current
    fun open(i: Intent): Boolean = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    fun appInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
    Glass(Modifier.fillMaxWidth(), pad = 16.dp, borderColor = (if (s.bgOk) Sky.Ok else Sky.Bad).copy(alpha = 0.55f)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(Icons.Rounded.BatteryChargingFull, "ماندن در پس‌زمینه", "تا وقتی داخل بازی هستی یا برنامه را از لیست اخیر می‌بندی، ویس قطع نشود")
            Chip(if (s.bgOk) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                if (s.bgOk) "اجازه‌ی باتری داده شده" else "اجازه‌ی باتری هنوز داده نشده", if (s.bgOk) Sky.Ok else Sky.Bad)
            SkyButton("۱) بدون محدودیت باتری", Icons.Rounded.BatteryChargingFull, Modifier.fillMaxWidth()) {
                if (!open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))))
                    if (!open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) appInfo()
            }
            SkyButton("۲) شروع خودکار (مخصوص برند گوشی)", Icons.Rounded.Bolt, Modifier.fillMaxWidth()) {
                // صفحه‌ی «شروع خودکار» هر برند جای جدایی دارد؛ همه را امتحان می‌کنیم و اگر هیچ‌کدام نبود، تنظیمات خود برنامه باز می‌شود
                val pairs = listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
                    "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                    "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
                )
                var done = false
                for ((pkg, cls) in pairs) { if (open(Intent().setComponent(android.content.ComponentName(pkg, cls)))) { done = true; break } }
                if (!done) appInfo()
            }
            SkyButton("۳) تنظیمات خود برنامه", Icons.Rounded.Settings, Modifier.fillMaxWidth()) { appInfo() }
            Text("نکته: داخل لیست برنامه‌های اخیر، روی کارت «رویال ویس» نگه دار و «قفل» را بزن تا با پاک‌کردن همه‌ی برنامه‌ها بسته نشود. وقتی ویس وصل است، اعلان دائمی «رویال ویس» را می‌بینی؛ اگر پاک شد یعنی سیستم برنامه را بسته و باید دوباره بازش کنی.",
                color = Sky.Ink2, fontSize = 11.sp)
        }
    }
}

@Composable
fun SettingsScreen(s: Ui) {
    val c = LocalContext.current
    fun set(k: String, on: Boolean) = VoiceService.cmd(c, VoiceService.SET, "k" to k, "v" to if (on) "1" else "0")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات", color = Sky.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        ProfileCard(s)
        BackgroundSection(s)
        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            SectionTitle(Icons.Rounded.Mic, "صدا")
            SwitchSetting(Icons.Rounded.TouchApp, "نگه دارید و صحبت کنید (PTT)", s.ptt) { set("ptt", it) }
            SliderSetting(Icons.Rounded.Mic, "بلندی میکروفون", s.micGain, 0.5f..3f, "mic", pct = true)
            SliderSetting(Icons.Rounded.VolumeUp, "بلندی صدای بقیه", s.outGain, 0f..2f, "out", pct = true)
        }
        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            SectionTitle(Icons.Rounded.GraphicEq, "حساسیت میکروفون")
            val thr = vadThreshold(s.vad)
            LevelMeter(if (s.state == St.Connected) s.level else 0f, (kotlin.math.ln(1.0 + thr) / kotlin.math.ln(4001.0)).toFloat())
            Text(if (s.state == St.Connected) "موقع صحبت، نوار باید از خط نارنجی رد شود. اگر نویز محیط رد می‌شود حساسیت را کم کن."
                 else "برای دیدن سطح صدا، اول به ویس وصل شو.", color = Sky.Ink2, fontSize = 12.sp)
            SliderSetting(Icons.Rounded.GraphicEq, "حساسیت", s.vad, 0f..1f, "vad", pct = true)
        }
        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            SectionTitle(Icons.Rounded.Headset, "صدای جهت‌دار")
            SwitchSetting(Icons.Rounded.Headset, "چپ و راست، جلو و عقب (با هدفون)", s.stereo) { set("stereo", it) }
            Text("جهت صدا از روی سمتی که داخل بازی به آن نگاه می‌کنی و جای گوینده حساب می‌شود.", color = Sky.Ink2, fontSize = 12.sp)
        }
        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            SectionTitle(Icons.Rounded.Layers, "دکمه‌ی شناور روی بازی")
            SwitchSetting(Icons.Rounded.Layers, "نمایش دکمه‌ی شناور", s.overlay) { on ->
                if (on && !Settings.canDrawOverlays(c))
                    c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
                else VoiceService.cmd(c, VoiceService.OVERLAY, "on" to if (on) "1" else "0")
            }
            SwitchSetting(Icons.Rounded.Tune, "حالت فشرده", s.compact) { set("compact", it) }
            SwitchSetting(Icons.Rounded.Groups, "نمایش اعضای تیم (و میکروفون بسته‌شان)", s.ovTeam) { set("ovTeam", it) }
            SwitchSetting(Icons.Rounded.RecordVoiceOver, "نمایش بقیه موقع حرف زدن", s.ovOthers) { set("ovOthers", it) }
            SliderSetting(Icons.Rounded.ZoomIn, "اندازه‌ی شناور", s.scale, 0.7f..1.6f, "scale", pct = true)
        }
        Glass(Modifier.fillMaxWidth(), pad = 16.dp) {
            SectionTitle(Icons.Rounded.Person, "صدای هر بازیکن", "کم، زیاد یا صفر")
            if (s.seen.isEmpty()) Text("هر کس که صدایش را بشنوی اینجا می‌آید.", color = Sky.Ink2, fontSize = 12.sp)
            s.seen.reversed().forEach { n -> PlayerVolume(n, s.vols[n.lowercase()] ?: 1f) }
        }
        if (s.state == St.Connected) TopCard(s)
        Spacer(Modifier.height(8.dp))
    }
}


// ───────────────────────── آپدیت ─────────────────────────
private fun httpBase(raw: String): String {
    val t = raw.trim().trimEnd('/').removeSuffix("/ws")
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.startsWith("ws://") -> "http://" + t.removePrefix("ws://")
        t.startsWith("wss://") -> "https://" + t.removePrefix("wss://")
        t.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(t) -> "http://$t"
        else -> "https://$t"
    }
}

private fun downloadApk(c: Context, link: String, onProgress: (Float) -> Unit): File {
    val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val f = File(dir, "update.apk")
    OkHttpClient().newCall(Request.Builder().url(link).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        val body = r.body ?: throw IOException("empty")
        val total = body.contentLength()
        body.byteStream().use { i ->
            f.outputStream().use { o ->
                val buf = ByteArray(16 * 1024); var done = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    }
    return f
}

private fun installApk(c: Context, f: File) {
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)
    c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** صفحه‌ی آپدیت: متن را خودت در update.json می‌نویسی. اگر force باشد راه برگشت ندارد. */
@Composable
fun UpdateScreen(info: UpdateInfo, onClose: (() -> Unit)?) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(-1f) }
    var status by remember { mutableStateOf("") }
    val base = remember { httpBase(BuildConfig.DEFAULT_SERVER) }
    val link = "$base/update/${Uri.encode(info.apk)}"
    BackHandler(enabled = onClose == null) { }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(84.dp).shadow(16.dp, CircleShape, spotColor = Sky.Blue, ambientColor = Sky.Blue).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF5CC3FF), Sky.Blue2))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Text(if (info.force) "آپدیت لازم است" else "آپدیت جدید", color = Sky.Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("نسخه‌ی ${info.name}", color = Sky.Ink, fontSize = 14.sp)
        Glass(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(info.notes.ifBlank { "نسخه‌ی جدید برنامه آماده است." }, color = Sky.Ink, fontSize = 15.sp, lineHeight = 24.sp)
            }
        }
        if (progress >= 0f) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.7f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF45B6FF), Sky.Blue2))))
            }
            Text("${(progress * 100).toInt()}٪", color = Sky.Ink, fontSize = 13.sp)
        }
        if (status.isNotEmpty()) Chip(Icons.Rounded.Warning, status, Sky.Bad)
        Spacer(Modifier.weight(1f, fill = true))
        SkyButton(if (progress in 0f..0.999f) "در حال دانلود…" else "دانلود و نصب", Icons.Rounded.Download,
            Modifier.fillMaxWidth(), enabled = progress < 0f || progress >= 1f) {
            if (info.apk.isBlank()) { status = "فایل آپدیت روی سرور تنظیم نشده."; return@SkyButton }
            if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
                status = "اجازه‌ی «نصب برنامه‌ها» را روشن کن، برگرد و دوباره بزن."
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else scope.launch {
                progress = 0f; status = ""
                try {
                    val f = withContext(Dispatchers.IO) { downloadApk(c, link) { p -> progress = p } }
                    progress = 1f; installApk(c, f)
                } catch (e: Exception) { progress = -1f; status = "دانلود ناموفق بود: ${e.message ?: ""}" }
            }
        }
        TextButton({ c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
            Text("باز کردن لینک دانلود در مرورگر", color = Sky.Ink, fontSize = 13.sp)
        }
        if (onClose != null) TextButton(onClose) { Text("بعداً", color = Sky.Ink) }
    }
}
