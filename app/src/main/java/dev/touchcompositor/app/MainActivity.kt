package dev.touchcompositor.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val workInFlight = AtomicBoolean(false)
    private lateinit var environment: RuntimeEnvironment

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var setupCard: View
    private lateinit var setupTitle: TextView
    private lateinit var setupDescription: TextView
    private lateinit var readyCard: View
    private lateinit var overlayDot: View
    private lateinit var overlayText: TextView
    private lateinit var startButton: Button

    private var runtimeReady = false
    private var destroyed = false

    private val bg = Color.rgb(246, 247, 249)
    private val card = Color.WHITE
    private val ink = Color.rgb(25, 28, 33)
    private val muted = Color.rgb(107, 113, 122)
    private val accent = Color.rgb(48, 99, 211)
    private val green = Color.rgb(42, 150, 98)
    private val red = Color.rgb(199, 66, 63)
    private val amber = Color.rgb(185, 126, 42)

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (!destroyed) runOnUiThread { refreshShizuku() }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        if (!destroyed) runOnUiThread { showNeedSetup("تم إيقاف Shizuku، يرجى إعادة التشغيل") }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 100 && !destroyed) runOnUiThread {
            if (result == PackageManager.PERMISSION_GRANTED) ensureEnvironmentAsync()
            else showNeedSetup("لم يتم منح إذن Shizuku، يمكنك تعديله من إدارة تطبيقات Shizuku")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        environment = RuntimeEnvironment(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR }
        setContentView(buildUi())
        refreshOverlayPermission()
        showNeedSetup("يرجى تشغيل Shizuku أولاً، ثم منح الإذن لهذا التطبيق")
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission()
        refreshShizuku()
    }

    private fun refreshShizuku() {
        if (RuntimeEnvironment.authorized) ensureEnvironmentAsync()
        else showNeedSetup(if (RuntimeEnvironment.available) "يرجى منح هذا التطبيق إذن استخدام Shizuku" else "يرجى تشغيل الخدمة في Shizuku أولاً")
    }

    override fun onDestroy() {
        destroyed = true
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        environment.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(40))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Touch Compositor"
            textSize = 28f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "تشغيل تركيب اللمس عبر Shizuku"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(22))
        })

        root.addView(statusCard())
        root.addView(gap())
        setupCard = setupCardView()
        root.addView(setupCard)
        root.addView(gap())
        readyCard = readyCardView()
        root.addView(readyCard)

        root.addView(TextView(this).apply {
            text = "يتم إدارة الاقتران والتشغيل للتصحيح اللاسلكي بواسطة Shizuku. هذا التطبيق يطلب فقط إذن Shizuku لتشغيل خدمة تركيب اللمس."
            textSize = 11.5f
            setTextColor(muted)
            setLineSpacing(0f, 1.18f)
            setPadding(dp(3), dp(10), dp(3), 0)
        })
        return scroll
    }

    private fun statusCard(): View = cardLayout().apply {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this@MainActivity).apply { background = circle(amber) }
        row.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(11) })
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this@MainActivity).apply {
            text = "جارٍ الفحص"
            textSize = 16f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusDetail = TextView(this@MainActivity).apply {
            text = ""
            textSize = 11.5f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
            maxLines = 4
        }
        texts.addView(statusTitle)
        texts.addView(statusDetail)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(row)
    }

    private fun setupCardView(): View = cardLayout().apply {
        setupTitle = cardTitle("إعداد Shizuku")
        addView(setupTitle)
        setupDescription = bodyText("قم بتشغيل الخدمة في Shizuku أولاً، ثم عد هنا للمنح والتشغيل.")
        addView(setupDescription)
        addView(primaryButton("منح الإذن والتشغيل") {
            when {
                !RuntimeEnvironment.available -> showNeedSetup("يرجى فتح Shizuku وتشغيل الخدمة أولاً")
                RuntimeEnvironment.authorized -> ensureEnvironmentAsync()
                else -> runCatching { Shizuku.requestPermission(100) }
                    .onFailure { showNeedSetup(it.message ?: "فشل طلب الإذن") }
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        addView(secondaryButton("فتح Shizuku") { openShizuku() }, LinearLayout.LayoutParams(-1, dp(46)))
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) startActivity(intent)
        else showNeedSetup("لم يتم العثور على Shizuku، يرجى تثبيته وتشغيله أولاً")
    }

    private fun readyCardView(): View = cardLayout().apply {
        visibility = View.GONE
        addView(cardTitle("جاهز للاستخدام"))
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        overlayDot = View(this@MainActivity).apply { background = circle(Color.GRAY) }
        row.addView(overlayDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
        overlayText = TextView(this@MainActivity).apply {
            text = "فحص إذن النافذة العائمة"
            textSize = 13f
            setTextColor(ink)
        }
        row.addView(overlayText, LinearLayout.LayoutParams(0, dp(40), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        row.addView(textButton("الإعدادات") { requestOverlayPermission() })
        addView(row)

        startButton = primaryButton("تشغيل الأدوات العائمة") { launchOverlay() }
        addView(startButton, LinearLayout.LayoutParams(-1, dp(52)))

        val actions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        actions.addView(textButton("إعادة تشغيل البيئة") { restartEnvironment() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        actions.addView(textButton("إدارة إذن Shizuku") { openShizuku() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        addView(actions)
    }

    private fun ensureEnvironmentAsync() {
        if (!workInFlight.compareAndSet(false, true)) return
        showBusy("جارٍ تشغيل البيئة", "جارٍ تشغيل خدمة اللمس عبر Shizuku…")
        io.execute {
            val result = environment.ensureReady()
            workInFlight.set(false)
            runOnUiThread {
                if (!destroyed) result.onSuccess { showReady() }
                    .onFailure { showNeedSetup(it.message ?: "تعذر تشغيل خدمة اللمس") }
            }
        }
    }

    private fun restartEnvironment() {
        if (!workInFlight.compareAndSet(false, true)) return
        runtimeReady = false
        showBusy("جارٍ إعادة تشغيل البيئة", "")
        io.execute {
            val result = environment.restart()
            workInFlight.set(false)
            runOnUiThread {
                if (!destroyed) result.onSuccess { showReady() }
                    .onFailure { showNeedSetup(it.message ?: "فشلت إعادة التشغيل") }
            }
        }
    }

    private fun launchOverlay() {
        if (!runtimeReady) {
            ensureEnvironmentAsync()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        moveTaskToBack(true)
    }

    private fun showReady() {
        runtimeReady = true
        statusDot.background = circle(green)
        statusTitle.text = "البيئة جاهزة"
        statusTitle.setTextColor(green)
        statusDetail.text = "يمكنك تشغيل الأدوات العائمة مباشرة"
        setupCard.visibility = View.GONE
        readyCard.visibility = View.VISIBLE
        refreshOverlayPermission()
    }

    private fun showNeedSetup(detail: String) {
        runtimeReady = false
        statusDot.background = circle(amber)
        statusTitle.text = "تحتاج إلى خدمة Shizuku والإذن"
        statusTitle.setTextColor(amber)
        statusDetail.text = detail
        setupCard.visibility = View.VISIBLE
        readyCard.visibility = View.GONE
    }

    private fun showBusy(title: String, detail: String) {
        runtimeReady = false
        statusDot.background = circle(amber)
        statusTitle.text = title
        statusTitle.setTextColor(amber)
        statusDetail.text = detail
        setupCard.visibility = View.GONE
        readyCard.visibility = View.GONE
    }

    private fun refreshOverlayPermission() {
        if (!::overlayDot.isInitialized) return
        val allowed = Settings.canDrawOverlays(this)
        overlayDot.background = circle(if (allowed) green else red)
        overlayText.text = if (allowed) "تم منح إذن النافذة العائمة" else "تحتاج إلى إذن النافذة العائمة"
        overlayText.setTextColor(if (allowed) green else red)
        startButton.alpha = if (allowed) 1f else 0.75f
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(16), dp(17), dp(17))
        background = rounded(card, 17f, Color.rgb(226, 229, 234))
        elevation = dp(1).toFloat()
    }

    private fun cardTitle(value: String) = TextView(this).apply {
        text = value; textSize = 16f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
    }

    private fun bodyText(value: String) = TextView(this).apply {
        text = value; textSize = 12.5f; setTextColor(muted); setLineSpacing(0f, 1.18f)
    }

    private fun primaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
        background = rounded(accent, 12f); setOnClickListener { click() }
    }

    private fun secondaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(ink)
        background = rounded(Color.rgb(249, 250, 252), 12f, Color.rgb(215, 219, 225)); setOnClickListener { click() }
    }

    private fun textButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(accent)
        setPadding(dp(8), 0, dp(8), 0); setOnClickListener { click() }
    }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
