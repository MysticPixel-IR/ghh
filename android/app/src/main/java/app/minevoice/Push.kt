package app.minevoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * اعلان وقتی برنامه بسته است (FCM). فقط وقتی app/google-services.json داخل پروژه باشد و سرور FCM_SERVICE_ACCOUNT داشته باشد کار می‌کند؛
 * در غیر این صورت هیچ‌چیز عوض نمی‌شود و برنامه مثل قبل است. داخل اعلان «متن پیام» نیست، فقط اسم فرستنده و نوع (حریم خصوصی/ایمنی).
 * توجه: FCM به سرویس‌های گوگل روی گوشی و دسترسی سرور به googleapis نیاز دارد؛ بدون آن‌ها اعلانی نمی‌رسد.
 */
object Push {
    @Volatile var sender: ((String) -> Unit)? = null   // VoiceService بعد از ورود می‌گذارد تا توکن تازه فوراً ثبت شود
    private fun sp(c: Context) = c.getSharedPreferences("push", Context.MODE_PRIVATE)
    fun token(c: Context): String = sp(c).getString("tok", "") ?: ""

    fun saveToken(c: Context, t: String) {
        sp(c).edit().putString("tok", t).apply()
        regMsg(c)?.let { sender?.invoke(it) }
    }

    /** Firebase پیکربندی شده؟ (بدون google-services.json نه) */
    fun available(c: Context): Boolean = try { FirebaseApp.getApps(c).isNotEmpty() } catch (e: Throwable) { false }

    fun init(c: Context) {
        if (!available(c)) return
        try { FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> if (!t.isNullOrEmpty()) saveToken(c, t) } } catch (e: Throwable) { }
    }

    fun regMsg(c: Context): String? {
        val t = token(c); if (t.isEmpty()) return null
        return JSONObject().put("t", "push.reg").put("tok", t)
            .put("dm", NotifPrefs.get(c, "dm", true)).put("group", NotifPrefs.get(c, "group", true)).put("call", NotifPrefs.get(c, "call", true)).toString()
    }

    fun offMsg(c: Context): String? { val t = token(c); return if (t.isEmpty()) null else JSONObject().put("t", "push.off").put("tok", t).toString() }

    /** اعلان داده‌ی FCM: ترجیحات کاربر همین‌جا هم اعمال می‌شود (تغییر تنظیمات فوراً اثر دارد، حتی قبل از اتصال بعدی) */
    fun show(c: Context, d: Map<String, String>) {
        if (Chats.appVisible) return
        val kind = d["k"] ?: return
        if (kind != "dm" && kind != "group" && kind != "call") return
        if (!NotifPrefs.get(c, kind, true)) return
        val from = (d["f"] ?: "").take(32).ifBlank { "یک بازیکن" }
        val chat = (d["c"] ?: "").take(64)
        val title = when (kind) { "call" -> "ویس‌کال"; "group" -> "گروه چت"; else -> from }
        val text = when (kind) { "call" -> "$from یک ویس‌کال فرستاد"; "group" -> "پیام جدید از $from"; else -> "پیام جدید" }
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("chat", "پیام‌ها", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(c, 5, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(1000 + (chat.hashCode() and 0xFFFF), NotificationCompat.Builder(c, "chat").setSmallIcon(R.drawable.ic_stat_mic)
                .setColor(0xFF1E9BFF.toInt()).setContentTitle(title).setContentText(text)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true).setContentIntent(open).build())
        } catch (e: Exception) { }
    }
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Push.saveToken(applicationContext, token) }
    override fun onMessageReceived(message: RemoteMessage) { Push.show(applicationContext, message.data) }
}
