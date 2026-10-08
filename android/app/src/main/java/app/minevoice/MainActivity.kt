package app.minevoice

import android.graphics.Color as AColor
import android.os.Bundle
import kotlinx.coroutines.flow.update
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class Tab(val label: String, val icon: ImageVector) {
    CONNECT("اتصال", Icons.Rounded.Link),
    HOME("صدا", Icons.Rounded.Mic),
    GROUPS("گروه‌ها", Icons.Rounded.Groups),
    ADMIN("مدیریت", Icons.Rounded.AdminPanelSettings),
    SETTINGS("تنظیمات", Icons.Rounded.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        VoiceService.ui.update { it.copy(bgOk = VoiceService.bgOk(this)) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT))
        if (VoiceService.ui.value.state == St.Idle) VoiceService.ui.value = VoiceService.load(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Sky.Blue, onPrimary = Color.White,
                    background = Sky.Light, surface = Color.White, onSurface = Sky.Ink)) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val s by VoiceService.ui.collectAsState()
    val active = s.state == St.Connecting || s.state == St.Connected || s.state == St.Reconnecting
    val tabs = when {
        s.state == St.Connected -> listOfNotNull(Tab.HOME, Tab.GROUPS, if (s.admin) Tab.ADMIN else null, Tab.SETTINGS)
        active -> emptyList()
        else -> listOf(Tab.CONNECT, Tab.SETTINGS)
    }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showUpd by remember { mutableStateOf(false) }
    val upd = s.update
    val cur: Tab? = if (tab in tabs) tab else tabs.firstOrNull()

    SkyBackground {
      if (upd != null && (upd.force || showUpd)) {
        UpdateScreen(upd, if (upd.force) null else { { showUpd = false } })
      } else {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(cur, transitionSpec = { (fadeIn(tween(260)) + scaleIn(initialScale = 0.96f, animationSpec = tween(260))) togetherWith fadeOut(tween(140)) }, label = "tabs") { t ->
                    when (t) {
                        Tab.CONNECT -> ConnectScreen(s)
                        Tab.HOME -> HomeScreen(s) { showUpd = true }
                        Tab.GROUPS -> GroupsScreen(s)
                        Tab.ADMIN -> AdminScreen(s)
                        Tab.SETTINGS -> SettingsScreen(s)
                        null -> ConnectingScreen(s)
                    }
                }
            }
            if (tabs.isNotEmpty()) NavBar(tabs, cur) { tab = it }
        }
      }
        NoticeBanner(s)
    }
}

@Composable
private fun NavBar(tabs: List<Tab>, cur: Tab?, onSelect: (Tab) -> Unit) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()
        .shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = Sky.Blue.copy(alpha = 0.35f), spotColor = Sky.Blue.copy(alpha = 0.45f))
        .clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.96f)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { t ->
            val sel = t == cur
            val bg by animateColorAsState(if (sel) Sky.Blue else Color.Transparent, tween(250), label = "nb")
            val fg by animateColorAsState(if (sel) Color.White else Sky.Ink2, tween(250), label = "nf")
            val sc by animateFloatAsState(if (sel) 1.18f else 1f, spring(dampingRatio = 0.45f, stiffness = 380f), label = "ns")
            Column(Modifier.weight(1f).pressScale({ onSelect(t) }).clip(RoundedCornerShape(22.dp)).background(bg).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(t.icon, null, tint = fg, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = sc; scaleY = sc })
                Text(t.label, color = fg, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun BoxScope.NoticeBanner(s: Ui) {
    var text by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(s.noticeId) {
        if (s.noticeId > 0) { text = s.notice; show = true; delay(3200); show = false }
    }
    AnimatedVisibility(show,
        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp),
        enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Sky.Ink).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Info, null, tint = Color(0xFF8FD3FF), modifier = Modifier.size(20.dp))
            Text(text, color = Color.White, fontSize = 14.sp)
        }
    }
}
