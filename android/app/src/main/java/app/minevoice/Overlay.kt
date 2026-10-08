package app.minevoice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import kotlin.math.max
import kotlin.math.min

/**
 * دکمه‌ی شناور روی بازی.
 *
 * ساختار:   [دستگیره] [میکروفون] [هدست]
 *            نقطه‌ی رنگی + وضعیت (آنلاین / میوت / …)
 *            «تیم من»: اعضای تیم با وضعیت میکروفونشان (همیشه)
 *            «دیگر پلیرها»: فقط کسانی که الان حرف می‌زنند (بعد از سکوت پاک می‌شوند)
 *
 * - همه‌ی آیکون‌ها برداری‌اند (همان مسیرهای Material)، نه emoji؛ پس روی همه‌ی گوشی‌ها یک‌شکل و هم‌اندازه دیده می‌شوند.
 * - اندازه‌ی دکمه‌ها ثابت است؛ با عوض شدن وضعیت، پنجره جابه‌جا یا بزرگ‌وکوچک نمی‌شود.
 * - جهت چیدمان همیشه چپ‌به‌راست است (با زبان فارسی گوشی هم دکمه‌ها جابه‌جا نمی‌شوند).
 * - از لبه‌ی صفحه بیرون نمی‌رود (چرخش صفحه هم درست می‌شود) و بعد از رها کردن به نزدیک‌ترین لبه می‌چسبد.
 * - هیچ انیمیشن دائمی ندارد؛ حلقه‌ی صحبت فقط همان لحظه که حرف می‌زنی می‌چرخد.
 */
class Overlay(
    private val c: Context,
    private val onTap: () -> Unit,
    private val onPtt: (Boolean) -> Unit,
    private val onMove: (Int, Int) -> Unit,
    private val onDeaf: () -> Unit
) {
    private companion object {
        const val BLUE = 0xFF1E9BFF.toInt()
        const val OK = 0xFF16A877.toInt()
        const val BAD = 0xFFEF4466.toInt()
        const val WARN = 0xFFD98A00.toInt()
        const val GRAY = 0xFF6B8CAA.toInt()
        const val SOFT = 0xFF8FB3D1.toInt()
    }

    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var mic: RoundBtn? = null
    private var deaf: RoundBtn? = null
    private var dot: View? = null
    private var st: TextView? = null
    private var statusRow: View? = null
    private var list: LinearLayout? = null
    private var d = 1f
    private var builtScale = -1f
    private var builtCompact = false
    private var ptt = false
    private var lastTalking = false
    private var lastStatus = ""
    private var lastSig = ""
    private var snap: ValueAnimator? = null
    private val fade = Runnable { root?.animate()?.alpha(0.62f)?.setDuration(450)?.start() }
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        // پنجره‌های سرویس به‌طور پیش‌فرض نرم‌افزاری رسم می‌شوند؛ شتاب سخت‌افزاری یعنی نرم‌تر و سبک‌تر
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    val shown get() = root != null

    private fun px(v: Float) = (v * d).toInt().coerceAtLeast(1)

    /** پررنگ می‌شود و بعد از چند ثانیه بی‌کاری کم‌رنگ */
    private fun wake() {
        val r = root ?: return
        h.removeCallbacks(fade)
        r.animate().cancel()
        r.alpha = 1f
        h.postDelayed(fade, 4500)
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = 999f; setColor(color) }

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun clampValues() {
        val r = root ?: return
        val (sw, sh) = screen()
        val rw = if (r.width > 0) r.width else r.measuredWidth
        val rh = if (r.height > 0) r.height else r.measuredHeight
        lp.x = lp.x.coerceIn(0, max(0, sw - rw))
        lp.y = lp.y.coerceIn(0, max(0, sh - rh))
    }

    /** از VoiceService بعد از چرخش صفحه صدا زده می‌شود تا دکمه بیرون از صفحه نماند */
    fun clamp() {
        val r = root ?: return
        clampValues()
        runCatching { wm.updateViewLayout(r, lp) }
    }

    private fun snapToEdge() {
        val r = root ?: return
        val (sw, _) = screen()
        val margin = px(6f)
        val target = if (lp.x + r.width / 2 < sw / 2) margin else max(margin, sw - r.width - margin)
        snap?.cancel()
        if (target == lp.x) { onMove(lp.x, lp.y); return }
        snap = ValueAnimator.ofInt(lp.x, target).apply {
            duration = 240
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                lp.x = it.animatedValue as Int
                root?.let { v -> runCatching { wm.updateViewLayout(v, lp) } }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { clampValues(); onMove(lp.x, lp.y) }
            })
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(): View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; snap?.cancel(); wake() }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    clampValues()
                    root?.let { runCatching { wm.updateViewLayout(it, lp) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> snapToEdge()
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun micListener(): View.OnTouchListener = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake(); v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                if (ptt) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onPtt(true) }
            }
            MotionEvent.ACTION_UP -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                if (ptt) onPtt(false) else { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
                v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                if (ptt) onPtt(false)
            }
        }
        true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun deafListener(): View.OnTouchListener = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { wake(); v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start() }
            MotionEvent.ACTION_UP -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onDeaf(); v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        true
    }

    private fun inflate(u: Ui) {
        d = c.resources.displayMetrics.density * u.scale
        builtScale = u.scale; builtCompact = u.compact
        val compact = u.compact
        val drag = dragListener()

        val handle = HandleView(c).apply {
            layoutParams = LinearLayout.LayoutParams(px(22f), px(if (compact) 40f else 52f))
            contentDescription = "جابه‌جایی دکمه"
            setOnTouchListener(drag)
        }
        val m = RoundBtn(c, 0.8f).apply {
            val s = px(if (compact) 50f else 64f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            contentDescription = "میکروفون"
            setOnTouchListener(micListener())
        }
        val dBtn = RoundBtn(c, 1f).apply {
            val s = px(if (compact) 34f else 40f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = px(2f) }
            contentDescription = "بستن صدای بقیه"
            setOnTouchListener(deafListener())
        }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(handle); addView(m); addView(dBtn)
        }
        val dotView = View(c).apply {
            layoutParams = LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(6f) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GRAY) }
        }
        val stView = TextView(c).apply {
            textSize = 11.5f * u.scale; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = px(165f)
            textDirection = View.TEXT_DIRECTION_RTL
        }
        val sRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(px(10f), px(1f), px(10f), px(3f))
            visibility = if (compact) View.GONE else View.VISIBLE
            addView(dotView); addView(stView)
            setOnTouchListener(drag) // ناحیه‌ی کشیدنِ بزرگ‌تر
        }
        val lst = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            visibility = View.GONE
        }
        root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(px(6f), px(6f), px(8f), px(6f))
            background = GradientDrawable().apply {
                cornerRadius = px(28f).toFloat(); setColor(0xF2EEF8FF.toInt()); setStroke(px(1f), 0x99FFFFFF.toInt())
            }
            addView(top); addView(sRow); addView(lst)
        }
        mic = m; deaf = dBtn; dot = dotView; st = stView; statusRow = sRow; list = lst
        lastStatus = ""; lastSig = ""; lastTalking = false
    }

    private fun rebuild(u: Ui) {
        val old = root ?: return
        runCatching { wm.removeView(old) }
        mic?.setPulse(false)
        inflate(u)
        runCatching { wm.addView(root, lp) }
        render(u)
        root?.post { clamp() }
    }

    fun show(x: Int, y: Int, u: Ui) {
        if (root != null || !Settings.canDrawOverlays(c)) return
        lp.x = x; lp.y = y
        inflate(u)
        runCatching { wm.addView(root, lp) }
        render(u)
        root?.post { clamp() }
        wake()
    }

    fun render(u: Ui) {
        if (root == null) return
        if (u.scale != builtScale || u.compact != builtCompact) { rebuild(u); return }
        val mb = mic ?: return
        val db = deaf ?: return
        ptt = u.ptt
        val talking = (u.pttDown || u.talking) && !u.staffMuted && !u.deaf

        // ----- میکروفون -----
        val off = u.staffMuted || u.deaf || (u.muted && !u.ptt)
        mb.setIcon(when {
            u.staffMuted -> Ic.BLOCK
            off -> Ic.MIC_OFF
            u.ptt && !u.pttDown -> Ic.TOUCH
            else -> Ic.MIC
        })
        mb.setColor(when { off -> BAD; talking -> OK; else -> BLUE })
        mb.setPulse(talking)
        if (talking != lastTalking) { lastTalking = talking; wake() }

        // ----- هدست (بستن صدای بقیه) -----
        db.setIcon(Ic.HEADSET, strike = u.deaf)
        db.setColor(if (u.deaf) BAD else SOFT)

        // ----- وضعیت: نقطه = آنلاین بودن، متن = مهم‌ترین چیز -----
        val conn = u.state == St.Connected
        val presence = when {
            !conn -> GRAY
            u.inGame -> OK
            u.group.isNotEmpty() -> WARN
            else -> BAD
        }
        (dot?.background as? GradientDrawable)?.setColor(presence)
        val (statusText, statusColor) = when {
            !conn -> "در حال اتصال به ویس…" to GRAY
            u.staffMuted -> "ادمین میکروفونت را بسته" to BAD
            u.deaf -> "صدای بقیه بسته است" to BAD
            u.ptt -> (if (u.pttDown) "در حال ارسال…" else "نگه دار و صحبت کن") to (if (u.pttDown) OK else BLUE)
            u.muted -> "میکروفون خاموش" to BAD
            u.inGame -> "آنلاین داخل سرور" to OK
            u.group.isNotEmpty() -> "بیرون از سرور — فقط گروه" to WARN
            else -> "داخل سرور نیستی" to BAD
        }
        st?.apply {
            if (statusText != lastStatus) { lastStatus = statusText; text = statusText }
            setTextColor(statusColor)
        }
        statusRow?.visibility = if (u.compact) View.GONE else View.VISIBLE

        // ----- «تیم من» (همیشه، با وضعیت میکروفون) + «دیگر پلیرها» (فقط تا وقتی حرف می‌زنند) -----
        val myName = u.name.lowercase()
        val inTeam = u.group.isNotEmpty() && u.ovTeam
        val teamAll = if (inTeam) u.members.filter { it.lowercase() != myName } else emptyList()
        val team = teamAll.take(6)
        val speakingLc = u.speaking.map { it.lowercase() }.toSet()
        val offLc = u.membersOff.map { it.lowercase() }.toSet()
        val teamLc = u.members.map { it.lowercase() }.toSet()
        val others = if (u.ovOthers) u.speaking.filter { it.lowercase() !in teamLc && it.lowercase() != myName }.take(4) else emptyList()
        if (u.avatar.isNotBlank()) (team + others).forEach { if (Heads.cached(it) == null) Heads.prefetch(it, u.avatar) }
        // حالت هر عضو: ۲ = حرف می‌زند، ۱ = میکروفونش بسته است، ۰ = ساکت
        fun stateOf(n: String) = if (n.lowercase() in speakingLc) 2 else if (n.lowercase() in offLc) 1 else 0
        val sig = "T" + (if (inTeam) u.group else "") + ":" +
            team.joinToString("|") { it + stateOf(it) + (if (Heads.cached(it) != null) "+" else "-") } + "+" + (teamAll.size - team.size) +
            "/O:" + others.joinToString("|") { it + (if (Heads.cached(it) != null) "+" else "-") }
        val lst = list ?: return
        if (sig != lastSig) {
            lastSig = sig
            lst.removeAllViews()
            if (inTeam) {
                lst.addView(header(Ic.GROUP, "تیم من · " + u.group, BLUE))
                if (team.isEmpty()) lst.addView(hint("هنوز کسی همراهت نیست"))
                for (n in team) lst.addView(memberChip(n, stateOf(n), u))
                if (teamAll.size > team.size) lst.addView(hint("و " + (teamAll.size - team.size) + " نفر دیگر"))
            }
            if (others.isNotEmpty()) {
                lst.addView(header(Ic.SPEAKER, "دیگر پلیرها", OK))
                for (n in others) lst.addView(memberChip(n, 2, u))
            }
            if (team.any { stateOf(it) == 2 } || others.isNotEmpty()) wake()
            root?.post { clamp() } // لیست بلند یا کوتاه شد؛ از لبه‌ی پایین بیرون نزند
        }
        lst.visibility = if (u.compact || lst.childCount == 0) View.GONE else View.VISIBLE
    }

    /** عنوان بخش: آیکون کوچک + متن */
    private fun header(ic: Ic, label: String, color: Int): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(7f); marginStart = px(10f) }
        }
        row.addView(IconView(c, ic, color).apply { layoutParams = LinearLayout.LayoutParams(px(14f), px(14f)) })
        row.addView(TextView(c).apply {
            text = label; textSize = 11f * builtScale; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = px(150f)
            textDirection = View.TEXT_DIRECTION_RTL; setPadding(px(5f), 0, 0, 0)
        })
        return row
    }

    private fun hint(label: String): View = TextView(c).apply {
        text = label; textSize = 11f * builtScale; setTextColor(GRAY)
        textDirection = View.TEXT_DIRECTION_RTL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(3f); marginStart = px(12f) }
    }

    /** ردیف یک بازیکن: سر + اسم + وضعیت (سبز = حرف می‌زند، قرمز = میکروفون بسته، خاکستری = ساکت) */
    private fun memberChip(n: String, state: Int, u: Ui): View {
        val bg: Int; val fg: Int; val ic: Ic?; val icColor: Int
        when (state) {
            2 -> { bg = 0x2616A877; fg = 0xFF0B6B4B.toInt(); ic = Ic.SPEAKER; icColor = OK }
            1 -> { bg = 0x22EF4466; fg = 0xFFB3243F.toInt(); ic = Ic.MIC_OFF; icColor = BAD }
            else -> { bg = 0x1A6B8CAA; fg = 0xFF4A6B88.toInt(); ic = null; icColor = GRAY }
        }
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(px(4f), px(3f), px(10f), px(3f))
            background = pill(bg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(4f); marginStart = px(10f) }
            alpha = 0f
        }
        row.addView(AvatarView(c, n, Heads.cached(n)).apply { layoutParams = LinearLayout.LayoutParams(px(22f), px(22f)) })
        row.addView(TextView(c).apply {
            text = n; textSize = 12f * u.scale; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = px(110f)
            setPadding(px(6f), 0, px(6f), 0)
        })
        if (ic != null) row.addView(IconView(c, ic, icColor).apply { layoutParams = LinearLayout.LayoutParams(px(14f), px(14f)) })
        else row.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(px(7f), px(7f))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFA9BDD0.toInt()) }
        })
        row.animate().alpha(1f).setDuration(160).start()
        return row
    }

    fun hide() {
        h.removeCallbacks(fade)
        snap?.cancel(); snap = null
        mic?.setPulse(false)
        root?.let { runCatching { wm.removeView(it) } }
        root = null; mic = null; deaf = null; dot = null; st = null; statusRow = null; list = null
    }
}

// ───────────────────────── آیکون‌های برداری (Material، ۲۴×۲۴) ─────────────────────────
private enum class Ic(val data: String) {
    MIC("M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"),
    MIC_OFF("M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01v0.72c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z"),
    HEADSET("M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z"),
    BLOCK("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z"),
    TOUCH("M9,11.24V7.5C9,6.12 10.12,5 11.5,5S14,6.12 14,7.5v3.74c1.21,-0.81 2,-2.18 2,-3.74C16,5.01 13.99,3 11.5,3S7,5.01 7,7.5c0,1.56 0.79,2.93 2,3.74zM18.84,15.87l-4.54,-2.26c-0.17,-0.07 -0.35,-0.11 -0.54,-0.11H13v-6c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,6.67 10,7.5v10.74l-3.43,-0.72c-0.08,-0.01 -0.15,-0.03 -0.24,-0.03 -0.31,0 -0.59,0.13 -0.79,0.33l-0.79,0.8 4.94,4.94c0.27,0.27 0.65,0.44 1.06,0.44h6.79c0.75,0 1.33,-0.55 1.44,-1.28l0.75,-5.27c0.01,-0.07 0.02,-0.14 0.02,-0.2 0,-0.62 -0.38,-1.16 -0.91,-1.38z"),
    SPEAKER("M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"),
    GROUP("M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z");

    val path: Path by lazy { try { PathParser.createPathFromPathData(data) } catch (e: Exception) { Path() } }
}

/** آیکون ساده با رنگ دلخواه */
private class IconView(ctx: Context, private val ic: Ic, tint: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    override fun onDraw(cv: Canvas) {
        val s = min(width, height) / 24f
        cv.save(); cv.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f); cv.scale(s, s)
        cv.drawPath(ic.path, p); cv.restore()
    }
}

/** دکمه‌ی گرد: دایره‌ی رنگی + آیکون سفید + (اختیاری) حلقه‌ی صحبت. با خط‌خوردگی، آیکون «بریده» می‌شود. */
private class RoundBtn(ctx: Context, private val bodyFrac: Float) : View(ctx) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4.6f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.2f; color = Color.WHITE
    }
    private var icon = Ic.MIC
    private var strike = false
    private var color = 0xFF1E9BFF.toInt()
    private var colorAnim: ValueAnimator? = null
    private var ringT = 0f
    private var ringAnim: ValueAnimator? = null

    fun setIcon(i: Ic, strike: Boolean = false) {
        if (i == icon && strike == this.strike) return
        icon = i; this.strike = strike; invalidate()
    }

    fun setColor(c: Int) {
        if (c == color && colorAnim == null) return
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), color, c).apply {
            duration = 160
            addUpdateListener { color = it.animatedValue as Int; invalidate() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { colorAnim = null } })
            start()
        }
    }

    fun setPulse(on: Boolean) {
        if (on) {
            if (ringAnim != null) return
            ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { ringT = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (ringAnim != null) {
            ringAnim?.cancel(); ringAnim = null; ringT = 0f; invalidate()
        }
    }

    override fun onDetachedFromWindow() { colorAnim?.cancel(); ringAnim?.cancel(); ringAnim = null; super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val maxR = min(w, h) / 2f; val r = maxR * bodyFrac
        if (ringAnim != null) {
            ring.color = color; ring.alpha = ((1f - ringT) * 150).toInt(); ring.strokeWidth = maxR * 0.07f
            cv.drawCircle(cx, cy, r + (maxR - r) * ringT, ring)
        }
        body.color = color
        cv.drawCircle(cx, cy, r, body)
        val box = r * 1.12f; val s = box / 24f
        val saved = if (strike) cv.saveLayer(0f, 0f, w, h, null) else cv.save()
        cv.translate(cx - box / 2f, cy - box / 2f); cv.scale(s, s)
        cv.drawPath(icon.path, ink)
        if (strike) { cv.drawLine(4f, 3.4f, 20.6f, 20f, knock); cv.drawLine(4f, 3.4f, 20.6f, 20f, slash) }
        cv.restoreToCount(saved)
    }
}

/** شش نقطه‌ی دستگیره */
private class HandleView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9DB9D3.toInt() }
    override fun onDraw(cv: Canvas) {
        val r = width * 0.1f
        for (col in 0..1) for (row in 0..2)
            cv.drawCircle(width * (0.32f + 0.36f * col), height / 2f + (row - 1) * r * 3.6f, r, p)
    }
}

/** سر بازیکن (گرد)؛ اگر عکس نبود، حرف اول اسم روی یک دایره‌ی رنگی */
private class AvatarView(ctx: Context, name: String, private val bmp: Bitmap?) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val letter = name.take(1).uppercase()
    private val bg = intArrayOf(0xFF1E9BFF.toInt(), 0xFF16A877.toInt(), 0xFFF59E0B.toInt(), 0xFF8B5CF6.toInt(), 0xFFEF4466.toInt())[Math.floorMod(name.hashCode(), 5)]
    private val shader = bmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val m = Matrix()
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = min(w, h) / 2f
        if (shader != null && bmp != null) {
            m.setScale(w / bmp.width, h / bmp.height); shader.setLocalMatrix(m)
            p.shader = shader; cv.drawCircle(w / 2f, h / 2f, r, p)
        } else {
            p.shader = null; p.color = bg; cv.drawCircle(w / 2f, h / 2f, r, p)
            txt.textSize = h * 0.55f
            cv.drawText(letter, w / 2f, h / 2f - (txt.descent() + txt.ascent()) / 2f, txt)
        }
    }
}
