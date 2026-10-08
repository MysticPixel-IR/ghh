package app.minevoice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/** رنگ‌های تم آبی آسمانی */
object Sky {
    val Ink = Color(0xFF0B3A66)
    val Ink2 = Color(0xFF3B6288)
    val Blue = Color(0xFF1E9BFF)
    val Blue2 = Color(0xFF0B7FE0)
    val Light = Color(0xFFEAF7FF)
    val Ok = Color(0xFF16A877)
    val Warn = Color(0xFFF59E0B)
    val Bad = Color(0xFFEF4466)
}

/** پس‌زمینه‌ی آسمان: گرادیان نفس‌کش، ابرهای رونده و حباب‌های بالارونده */
@Composable
fun SkyBackground(content: @Composable BoxScope.() -> Unit) {
    val inf = rememberInfiniteTransition(label = "sky")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val sh by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sh")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // رنگ واقعی آسمان روز: آبی پررنگ‌تر بالا، که پایین‌تر روشن‌تر می‌شود (نه سفید)
            val top = lerp(Color(0xFF3AA6F4), Color(0xFF52B3F8), sh)
            val mid = lerp(Color(0xFF6DC1FA), Color(0xFF82CCFC), sh)
            val bottom = lerp(Color(0xFFA4DAFE), Color(0xFFB9E4FF), sh)
            drawRect(Brush.verticalGradient(listOf(top, mid, bottom)))
            val w = size.width; val h = size.height
            // درخشش خورشید: گوشه‌ی بالا، آرام نفس می‌کشد
            val glowC = Offset(w * 0.82f, h * 0.05f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.50f + 0.18f * sh), Color.Transparent), center = glowC, radius = w * 0.8f),
                radius = w * 0.8f, center = glowC)
            // ابرهای دور: کوچک‌تر و کندتر و کم‌رنگ‌تر
            for (i in 0 until 4) {
                val x = (((t * 0.5f + i * 0.31f) % 1f) * 1.6f - 0.3f) * w
                val y = h * (0.13f + i * 0.21f)
                val r = w * (0.09f + (i % 2) * 0.03f)
                drawCircle(Color.White.copy(alpha = 0.24f), r, Offset(x, y))
                drawCircle(Color.White.copy(alpha = 0.20f), r * 0.7f, Offset(x + r * 0.9f, y + r * 0.1f))
            }
            for (i in 0 until 5) {
                val speed = if (i % 2 == 0) 1 else 2
                val x = (((t * speed + i * 0.23f) % 1f) * 1.7f - 0.35f) * w
                val y = h * (0.07f + i * 0.17f)
                val r = w * (0.15f + (i % 3) * 0.05f)
                drawCircle(Color.White.copy(alpha = 0.42f), r, Offset(x, y))
                drawCircle(Color.White.copy(alpha = 0.36f), r * 0.7f, Offset(x + r * 0.9f, y + r * 0.15f))
                drawCircle(Color.White.copy(alpha = 0.36f), r * 0.6f, Offset(x - r * 0.8f, y + r * 0.2f))
            }
            for (i in 0 until 9) {
                val mult = 1 + i % 3
                val p = (t * mult * 2 + i * 0.11f) % 1f
                val x = w * ((i * 0.137f + 0.07f) % 1f) + sin(p * 6.28f + i) * 14f
                drawCircle(Color.White.copy(alpha = 0.4f * (1 - p)), 4f + (i % 4) * 3f, Offset(x, h * (1 - p)))
            }
        }
        content()
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, pad: Dp = 14.dp, borderColor: Color = Color.White.copy(alpha = 0.9f), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    // ورود نرم: کارت هنگام اولین نمایش کمی از پایین بالا می‌آید و پررنگ می‌شود
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "glass")
    Column(modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 28.dp.toPx() }
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.86f), Color(0xFFEFF9FF).copy(alpha = 0.66f))))
        .border(1.dp, Brush.verticalGradient(listOf(borderColor, borderColor.copy(alpha = 0.35f))), shape)
        .padding(pad), content = content)
}

/** حضور داخل سرور: نقطه‌ی سبز که نفس می‌کشد (آنلاین) یا نارنجی ثابت (خارج از سرور) */
@Composable
fun PresenceChip(inGame: Boolean) {
    val col = if (inGame) Sky.Ok else Sky.Warn
    val inf = rememberInfiniteTransition(label = "pr")
    val a by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pa")
    Row(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).graphicsLayer { alpha = if (inGame) a else 1f }.clip(CircleShape).background(col))
        Text(if (inGame) "آنلاین داخل سرور" else "خارج از سرور", color = col, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** فشار دادن: دکمه کمی کوچک می‌شود و با فنر برمی‌گردد */
@Composable
fun Modifier.pressScale(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "press")
    return this.graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

/** بارگذاری: حلقه‌های موج‌دار پشت یک آیکون که نفس می‌کشد */
@Composable
fun PulseLoader(icon: ImageVector, boxSize: Dp = 150.dp, color: Color = Sky.Blue) {
    val inf = rememberInfiniteTransition(label = "pl")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val sw by inf.animateFloat(0.93f, 1.07f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sw")
    Box(Modifier.size(boxSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0..2) {
                val q = (p + k / 3f) % 1f
                drawCircle(color.copy(alpha = 0.42f * (1f - q)), this.size.minDimension * (0.27f + 0.23f * q), style = Stroke(width = 3.dp.toPx()))
            }
        }
        Box(Modifier.size(boxSize * 0.46f).graphicsLayer { scaleX = sw; scaleY = sw }
            .shadow(14.dp, CircleShape, spotColor = color, ambientColor = color).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF5CC3FF), Sky.Blue2))), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(boxSize * 0.22f))
        }
    }
}

@Composable
fun Avatar(name: String, size: Dp = 40.dp, tint: Color = Sky.Blue) {
    // سر بازیکن از آدرس AVATAR_URL سرور؛ تا نیامده (یا اگر نبود) حرف اول اسم
    val tpl = VoiceService.ui.collectAsState().value.avatar
    val head by produceState<ImageBitmap?>(Heads.cached(name)?.asImageBitmap(), name, tpl) {
        if (value == null && tpl.isNotBlank()) value = withContext(Dispatchers.IO) { Heads.fetch(name, tpl)?.asImageBitmap() }
    }
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.25f), tint))),
        contentAlignment = Alignment.Center) {
        val b = head
        if (b != null) Image(b, name, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
        else Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun IconBtn(icon: ImageVector, tint: Color = Sky.Ink, bg: Color = Sky.Light, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).pressScale(onClick).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun Chip(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SkyButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val brush = if (danger) Brush.horizontalGradient(listOf(Color(0xFFFF7A90), Sky.Bad))
    else Brush.horizontalGradient(listOf(Color(0xFF45B6FF), Color(0xFF1E8CFF)))
    Row(modifier.pressScale(onClick, enabled).clip(RoundedCornerShape(16.dp)).background(brush, alpha = if (enabled) 1f else 0.45f)
        .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, icon: ImageVector, modifier: Modifier = Modifier,
          keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, leadingIcon = { Icon(icon, null) }, shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Sky.Blue, unfocusedBorderColor = Color(0xFFB5D9F2),
            focusedLabelColor = Sky.Blue, unfocusedLabelColor = Sky.Ink2,
            focusedLeadingIconColor = Sky.Blue, unfocusedLeadingIconColor = Sky.Ink2,
            focusedTextColor = Sky.Ink, unfocusedTextColor = Sky.Ink, cursorColor = Sky.Blue,
            focusedContainerColor = Color.White.copy(alpha = 0.6f), unfocusedContainerColor = Color.White.copy(alpha = 0.5f)))
}

/** نوار اکولایزر متحرک کنار اسم کسی که صحبت می‌کند */
@Composable
fun Equalizer(color: Color = Sky.Ok) {
    val inf = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 4) {
            val hh by inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(380 + i * 110, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(4.dp).fillMaxHeight(hh).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}
