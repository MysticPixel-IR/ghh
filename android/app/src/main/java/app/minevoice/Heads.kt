package app.minevoice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * سر بازیکن‌ها (عکس اسکین). آدرس از سرور ویس می‌آید (متغیر AVATAR_URL) و {name} با اسم بازیکن عوض می‌شود.
 * اگر آدرس خالی باشد یا عکس نیامد، به‌جایش حرف اول اسم نشان داده می‌شود.
 */
object Heads {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun cached(name: String): Bitmap? = cache[name.lowercase()]

    /** دانلود هم‌زمان؛ فقط روی ترد پس‌زمینه صدا بزن */
    fun fetch(name: String, tpl: String): Bitmap? {
        val k = name.lowercase()
        cache[k]?.let { return it }
        if (tpl.isBlank() || "{name}" !in tpl) return null
        val bad = failed[k]
        if (bad != null && System.currentTimeMillis() - bad < 120000L) return null
        return try {
            val u = URL(tpl.replace("{name}", URLEncoder.encode(name, "UTF-8").replace("+", "%20")))
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000
            val b = c.inputStream.use { BitmapFactory.decodeStream(it) }
            c.disconnect()
            if (b != null) { cache[k] = b; b } else { failed[k] = System.currentTimeMillis(); null }
        } catch (e: Exception) { failed[k] = System.currentTimeMillis(); null }
    }

    /** دانلود در پس‌زمینه برای دفعه‌ی بعد (اعلان‌ها) */
    fun prefetch(name: String, tpl: String) {
        val k = name.lowercase()
        if (cache.containsKey(k) || tpl.isBlank() || !loading.add(k)) return
        thread(isDaemon = true) { try { fetch(name, tpl) } finally { loading.remove(k) } }
    }
}
