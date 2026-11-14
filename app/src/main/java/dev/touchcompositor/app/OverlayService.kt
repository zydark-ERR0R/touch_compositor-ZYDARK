package dev.touchcompositor.app

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * واجهة عائمة مدمجة:
 * - مدخل علوي صغير يفتح وضع تحرير الماكرو وأدوات التحكم المدمجة
 * - التسجيل والإعدادات تستخدم نوافذ إدارة مؤقتة مقروءة
 * - مشغلات الماكرو تبقى غير مزعجة في الاستخدام العادي وتصبح قابلة للتحرير فقط في وضع التحرير
 */
class OverlayService : Service(), RuntimeClient.Listener, MacroRunner.Listener {
    companion object {
        const val ACTION_SHOW = "dev.touchcompositor.app.SHOW_OVERLAY"
        const val ACTION_HIDE = "dev.touchcompositor.app.HIDE_OVERLAY"
        private const val DOUBLE_TAP_MS = 240L
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = RuntimeClient()
    private lateinit var macroStore: MacroStore
    private lateinit var macroRunner: MacroRunner
    private lateinit var wm: WindowManager
    private lateinit var screenGeometry: LogicalScreenGeometry

    private var profiles = mutableListOf<MacroProfile>()
    private var activeProfileId: String? = null
    private var runtimeStatus = "بيئة التشغيل غير متصلة"
    private val runtimeRecoveryInFlight = AtomicBoolean(false)
    @Volatile private var serviceDestroying = false
    private var recording = false
    private var activeRecordingName: String? = null
    private enum class UiMode { NONE, RECORDINGS, MACROS, CONFIGS }
    private var uiMode = UiMode.NONE

    private var entryView: View? = null
    private val menuViews = mutableListOf<View>()
    private var recordingManagerView: View? = null
    private var macroManagerView: View? = null
    private var editorView: View? = null
    private var recordingPickerView: View? = null
    private var nameEditorView: View? = null
    private var recordingStopView: View? = null
    private var positionEditorView: View? = null
    private val triggerViews = linkedMapOf<String, View>()

    private var editingMacroId: String? = null
    private var selectedActionIndex: Int? = null
    private var runningMacroId: String? = null
    private var triggerGeometryGeneration = 0L
    @Volatile private var triggerRegionRevision = 0L
    private val rawTriggerHoldStarted = mutableSetOf<String>()
    private lateinit var routedWindows: RoutedOverlayWindows

    private val ink = Color.rgb(28, 31, 36)
    private val muted = Color.rgb(110, 116, 126)
    private val accent = Color.rgb(52, 103, 215)
    private val red = Color.rgb(204, 66, 63)
    private val green = Color.rgb(44, 151, 99)
    private val recordingLabels by lazy { getSharedPreferences("recording_labels", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        screenGeometry = LogicalScreenGeometry {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        }
        routedWindows = RoutedOverlayWindows(wm, screenGeometry, main) { command, done ->
            io.execute {
                val success = runCatching { if (!client.isConnected) client.connect(); client.request(command) }.isSuccess
                main.post { done(success) }
            }
        }
        macroStore = MacroStore(this)
        profiles = macroStore.loadProfiles()
        ensureProfile()
        activeProfileId = macroStore.activeProfileId()?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id.also { macroStore.saveActiveProfileId(it) }
        macroRunner = MacroRunner().also { it.listener = this }
        client.listener = this
        connectRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> removeAllWindows()
            else -> if (Settings.canDrawOverlays(this)) {
                showEntry()
                syncTriggerButtons()
            } else stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceDestroying = true
        routedWindows.close()
        cancelRawTriggerHolds()
        macroRunner.close()
        removeAllWindows()
        client.close()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post {
            hideMainMenu()
            hideRecordingManager()
            hideMacroManager()
            hideMacroEditor()
            hideRecordingPicker()
            hideNameEditor()
            editingMacroId = null
            selectedActionIndex = null
            if (positionEditorView != null) removePositionEditor()
            hideEntry()
            if (!recording) showEntry()
            if (recording) repositionRecordingStop() else {
                syncTriggerButtons()
                when (uiMode) {
                    UiMode.RECORDINGS -> showRecordingManager()
                    UiMode.CONFIGS -> showMacroManager()
                    UiMode.MACROS, UiMode.NONE -> Unit
                }
            }
        }
    }

    // بيئة التشغيل ---------------------------------------------------------------

    private fun connectRuntime() {
        io.execute {
            runCatching { client.connect() }
                .onSuccess { requestStatus() }
                .onFailure { t -> main.post { runtimeStatus = "بيئة التشغيل غير متصلة · ${t.message ?: "فشل الاتصال"}" } }
        }
    }

    override fun onConnected(ready: String) {
        main.post {
            runtimeStatus = ready.removePrefix("READY touch-runtime-v1 ")
            syncRuntimeTriggerRegions()
            routedWindows.sync()
        }
    }

    override fun onDisconnected(message: String) {
        if (serviceDestroying) return
        main.post { runtimeStatus = "بيئة التشغيل انقطعت · جارٍ الاستعادة"; cancelRawTriggerHolds(); if (macroRunner.isRunning) macroRunner.stop(); routedWindows.suspendRouting() }
        recoverRuntime()
    }

    private fun recoverRuntime() {
        if (serviceDestroying || !runtimeRecoveryInFlight.compareAndSet(false, true)) return
        io.execute {
            val environment = RuntimeEnvironment(this)
            val result = try {
                environment.ensureReady()
            } finally {
                environment.close()
            }
            runtimeRecoveryInFlight.set(false)
            if (serviceDestroying) return@execute
            result.onSuccess {
                runCatching { client.connect() }
                    .onSuccess { requestStatus() }
                    .onFailure { t -> main.post { runtimeStatus = "فشل استعادة بيئة التشغيل · ${t.message ?: "فشل إعادة الاتصال"}" } }
            }.onFailure { t ->
                main.post { runtimeStatus = "فشل استعادة بيئة التشغيل · ${t.message ?: "يرجى إعادة فتح الواجهة الرئيسية"}" }
            }
        }
    }

    override fun onEvent(line: String) {
        main.post {
            val parts = line.split(' ')
            when (parts.firstOrNull()) {
                "DISPLAY_CHANGED" -> {
                    if (!recording && positionEditorView == null) syncTriggerButtons()
                }
                "UI_TOUCH" -> routedWindows.onTouch(parts)
                "TRIGGER_DOWN" -> parts.getOrNull(1)?.let(::handleRawTriggerDown)
                "TRIGGER_UP" -> parts.getOrNull(1)?.let { handleRawTriggerUp(it, cancelled = false) }
                "TRIGGER_CANCEL" -> parts.getOrNull(1)?.let { handleRawTriggerUp(it, cancelled = true) }
            }
        }
    }

    override fun onMacroStarted(name: String) = Unit

    override fun onMacroFinished(name: String, stopped: Boolean, error: String?) {
        main.post {
            runningMacroId = null
            rawTriggerHoldStarted.clear()
            if (error != null) runtimeStatus = "خطأ في الماكرو · $error"
            refreshTriggerVisuals()
        }
    }

    /** تغييرات حالة الماكرو يجب ألا تعيد إنشاء نوافذ الطبقة العائمة. */
    private fun refreshTriggerVisuals() {
        triggerViews.forEach { (id, view) ->
            val editing = uiMode == UiMode.MACROS && editingMacroId == id
            val running = runningMacroId == id
            view.background = triggerBackground(editing, running)
        }
    }

    private fun handleRawTriggerDown(id: String) {
        if (uiMode != UiMode.NONE || recording) return
        val macro = activeProfile().macros.firstOrNull { it.id == id } ?: return
        triggerViews[id]?.background = triggerBackground(false, false)
        if (macro.trigger.mode != MacroTriggerMode.HOLD_REPEAT) return
        if (!macroRunner.isRunning && startMacro(macro, true, triggerViews[id])) {
            rawTriggerHoldStarted += id
        }
    }

    private fun handleRawTriggerUp(id: String, cancelled: Boolean) {
        val macro = activeProfile().macros.firstOrNull { it.id == id } ?: return
        if (id in rawTriggerHoldStarted) {
            rawTriggerHoldStarted.remove(id)
            if (runningMacroId == id) macroRunner.stop()
            return
        }
        if (!cancelled && uiMode == UiMode.NONE && macro.trigger.mode == MacroTriggerMode.CLICK_ONCE && !macroRunner.isRunning) {
            startMacro(macro, false, triggerViews[id])
        }
    }

    // المدخل العلوي + قائمة الوضع المدمجة ---------------------------------------

    private fun showEntry() {
        if (!Settings.canDrawOverlays(this) || entryView != null || recording) return
        val w = dp(44)
        val h = dp(16)
        val view = TextView(this).apply {
            text = "⌄"
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.argb(218, 255, 255, 255))
            background = rounded(Color.argb(112, 24, 27, 32), 8f)
            setOnClickListener {
                if (menuViews.isEmpty()) {
                    enterMacroEditMode()
                    showMainMenu()
                } else {
                    hideMainMenu()
                }
            }
        }
        val params = overlayParams(w, h, Gravity.TOP or Gravity.START).apply {
            x = ((resources.displayMetrics.widthPixels - w) / 2).coerceAtLeast(0)
            y = dp(2)
        }
        routedWindows.addView(view, params)
        entryView = view
    }

    private fun hideEntry() {
        entryView?.let { runCatching { routedWindows.removeView(it) } }
        entryView = null
    }

    private fun showMainMenu() {
        hideMainMenu()
        val size = dp(48)
        val gap = dp(12)
        val total = size * 4 + gap * 3
        val startX = ((resources.displayMetrics.widthPixels - total) / 2).coerceAtLeast(0)
        val y = dp(28)
        val specs = listOf(
            Triple("تسجيل", Color.argb(184, 190, 57, 55), {
                hideMainMenu()
                exitMacroEditMode(UiMode.RECORDINGS)
                showRecordingManager()
            }),
            Triple("إعدادات", Color.argb(184, 52, 103, 215), {
                hideMainMenu()
                exitMacroEditMode(UiMode.CONFIGS)
                showMacroManager()
            }),
            Triple("+ماكرو", Color.argb(176, 56, 137, 105), {
                hideMainMenu()
                if (uiMode != UiMode.MACROS) enterMacroEditMode()
                createMacro()
            }),
            Triple("إخفاء", Color.argb(170, 42, 46, 53), {
                hideMainMenu()
                exitMacroEditMode(UiMode.NONE)
            }),
        )
        specs.forEachIndexed { index, (label, color, action) ->
            val v = bubble(label, color, dp(24).toFloat()) { action() }
            val p = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
                x = startX + index * (size + gap); this.y = y
            }
            routedWindows.addView(v, p)
            menuViews += v
        }
    }

    private fun hideMainMenu() {
        menuViews.forEach { runCatching { routedWindows.removeView(it) } }
        menuViews.clear()
    }

    private fun enterMacroEditMode() {
        cancelRawTriggerHolds()
        hideRecordingManager()
        hideMacroManager()
        hideNameEditor()
        uiMode = UiMode.MACROS
        editingMacroId = null
        selectedActionIndex = null
        syncTriggerButtons()
    }

    private fun exitMacroEditMode(nextMode: UiMode) {
        cancelRawTriggerHolds()
        hideMacroEditor()
        hideRecordingPicker()
        hideNameEditor()
        editingMacroId = null
        selectedActionIndex = null
        uiMode = nextMode
        syncTriggerButtons()
    }

    /** الدخول إلى وضع التشغيل العادي من أي سطح إدارة مؤقت. */
    private fun enterRuntimeMode() {
        cancelRawTriggerHolds()
        editingMacroId = null
        selectedActionIndex = null
        uiMode = UiMode.NONE
        syncTriggerButtons()
    }

    // الإعدادات / وضع تحرير الماكرو -----------------------------------------------

    private fun activeProfile(): MacroProfile {
        ensureProfile()
        return profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first().also {
            activeProfileId = it.id
            macroStore.saveActiveProfileId(it.id)
        }
    }

    private fun ensureProfile() {
        if (profiles.isEmpty()) profiles += MacroProfile(name = "الإعداد 1")
    }

    private fun showMacroManager() {
        hideMacroManager()
        if (recording || uiMode != UiMode.CONFIGS) return
        val root = panelRoot()
        root.addView(panelHeader("الإعدادات") { hideMacroManager(); enterRuntimeMode() })

        val profileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profiles.forEach { profile ->
            profileRow.addView(pill(profile.name, profile.id == activeProfileId, false) {
                activeProfileId = profile.id
                macroStore.saveActiveProfileId(profile.id)
                editingMacroId = null; selectedActionIndex = null
                saveProfiles()
                hideMacroManager()
                enterRuntimeMode()
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) })
        }
        profileRow.addView(pill("+", false, true) { createProfile() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(profileRow, ViewGroup.LayoutParams(-2, dp(38)))
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        val active = activeProfile()
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameInput = compactInput(active.name, "اسم الإعداد")
        nameRow.addView(nameInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        nameRow.addView(compactTextButton("حفظ") {
            active.name = nameInput.text.toString().trim().ifBlank { "إعداد" }
            saveProfiles(); showMacroManager()
        }, LinearLayout.LayoutParams(dp(58), dp(40)).apply { rightMargin = dp(5) })
        nameRow.addView(compactTextButton("حذف") { deleteProfile(active) }, LinearLayout.LayoutParams(dp(58), dp(40)))
        root.addView(nameRow, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })

        root.addView(TextView(this).apply {
            text = "اختر إعداداً للدخول إلى وضع التشغيل العادي؛ افتح المدخل العلوي مرة أخرى لتعديل المشغلات."
            textSize = 10.5f; setTextColor(muted); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(7), dp(2), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(42)))

        val width = minOf(dp(330), resources.displayMetrics.widthPixels - dp(20))
        val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { x = dp(10); y = dp(58) }
        routedWindows.addView(root, p)
        macroManagerView = root
    }

    private fun hideMacroManager() {
        macroManagerView?.let { runCatching { routedWindows.removeView(it) } }
        macroManagerView = null
    }

    private fun createProfile() {
        val profile = MacroProfile(name = "الإعداد ${profiles.size + 1}")
        profiles += profile
        activeProfileId = profile.id
        saveProfiles(); macroStore.saveActiveProfileId(profile.id)
        syncTriggerButtons(); showMacroManager()
    }

    private fun deleteProfile(profile: MacroProfile) {
        if (profiles.size <= 1) return
        if (profile.macros.any { it.id == runningMacroId }) macroRunner.stop()
        profiles.removeAll { it.id == profile.id }
        activeProfileId = profiles.first().id
        macroStore.saveActiveProfileId(activeProfileId!!)
        editingMacroId = null; selectedActionIndex = null
        saveProfiles(); syncTriggerButtons(); showMacroManager()
    }

    private fun createMacro() {
        val profile = activeProfile()
        val n = profile.macros.size + 1
        val offset = (profile.macros.size % 6) * 0.065f
        val macro = TouchMacro(
            name = "ماكرو $n",
            trigger = MacroTrigger(MacroTriggerMode.CLICK_ONCE, 0.88f, (0.34f + offset).coerceAtMost(0.80f), 46f),
        )
        profile.macros += macro
        saveProfiles(); syncTriggerButtons(); showMacroEditor(macro)
    }

    // أزرار المشغل ---------------------------------------------------------------

    private fun syncTriggerButtons() {
        val generation = ++triggerGeometryGeneration
        hideTriggerButtons()
        if (Settings.canDrawOverlays(this) && !recording && positionEditorView == null) {
            activeProfile().macros.forEachIndexed { index, macro ->
                showMacroTrigger(macro, index, generation)
            }
        }
        syncRuntimeTriggerRegions(generation)
    }

    private fun hideTriggerButtons() {
        triggerViews.values.forEach { runCatching { routedWindows.removeView(it) } }
        triggerViews.clear()
    }

    private fun showMacroTrigger(macro: TouchMacro, index: Int, generation: Long) {
        val size = dp(macro.trigger.sizeDp).coerceAtLeast(dp(28))
        val (screenW, screenH) = screenGeometry.size()
        val desiredCenter = screenGeometry.toGlobal(macro.trigger.x, macro.trigger.y)
        val editing = uiMode == UiMode.MACROS && editingMacroId == macro.id
        val running = runningMacroId == macro.id
        val view = TextView(this).apply {
            text = (index + 1).toString() + if (macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) "↻" else ""
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            textSize = (macro.trigger.sizeDp * 0.27f).coerceIn(9f, 18f); typeface = Typeface.DEFAULT_BOLD
            background = triggerBackground(editing, running); elevation = dp(5).toFloat()
        }
        val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
            setFitInsetsTypes(0)
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            x = (desiredCenter.x - size / 2f).roundToInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
            y = (desiredCenter.y - size / 2f).roundToInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
        }
        if (uiMode == UiMode.MACROS) installTriggerTouch(view, params, macro, size)
        else view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> handleRawTriggerDown(macro.id)
                MotionEvent.ACTION_UP -> handleRawTriggerUp(macro.id, false)
                MotionEvent.ACTION_CANCEL -> handleRawTriggerUp(macro.id, true)
            }
            true
        }
        routedWindows.addView(view, params)
        triggerViews[macro.id] = view
        view.post {
            if (generation == triggerGeometryGeneration && view.isAttachedToWindow) {
                syncRuntimeTriggerRegions(generation)
            }
        }
    }

    private fun installTriggerTouch(view: View, params: WindowManager.LayoutParams, macro: TouchMacro, size: Int) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var holdStarted = false
        var startGlobalBounds: LogicalScreenGeometry.Rect? = null
        var lastTapAt = 0L; var pendingSingle: Runnable? = null
        val editable = { uiMode == UiMode.MACROS }
        val holdRunnable = Runnable {
            if (!moved && macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT && !macroRunner.isRunning) {
                holdStarted = startMacro(macro, true, view)
            }
        }
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    startGlobalBounds = screenGeometry.globalBounds(view)
                    moved = false; holdStarted = false
                    if (!editable() && macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) holdRunnable.run()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (editable()) {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                            moved = true; main.removeCallbacks(holdRunnable); pendingSingle?.let { main.removeCallbacks(it) }; pendingSingle = null; lastTapAt = 0L
                        }
                        if (moved && !holdStarted) {
                            val startBounds = startGlobalBounds
                            if (startBounds != null) {
                                val (screenW, screenH) = screenGeometry.size()
                                val targetLeft = (startBounds.left + dx).coerceIn(0f, (screenW - size).coerceAtLeast(0).toFloat())
                                val targetTop = (startBounds.top + dy).coerceIn(0f, (screenH - size).coerceAtLeast(0).toFloat())
                                params.x = startX + (targetLeft - startBounds.left).roundToInt()
                                params.y = startY + (targetTop - startBounds.top).roundToInt()
                                runCatching { routedWindows.updateViewLayout(view, params) }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    when {
                        editable() && moved && !holdStarted -> {
                            screenGeometry.normalizedCenter(view)?.let { center ->
                                macro.trigger.x = center.x
                                macro.trigger.y = center.y
                                saveProfiles()
                            }
                        }
                        holdStarted -> macroRunner.stop()
                        e.actionMasked == MotionEvent.ACTION_UP -> {
                            if (!editable()) {
                                if (macro.trigger.mode == MacroTriggerMode.CLICK_ONCE && !macroRunner.isRunning) startMacro(macro, false, view)
                            } else {
                                val now = SystemClock.uptimeMillis()
                                if (lastTapAt != 0L && now - lastTapAt <= DOUBLE_TAP_MS) {
                                    pendingSingle?.let { main.removeCallbacks(it) }; pendingSingle = null; lastTapAt = 0L; showMacroEditor(macro)
                                } else {
                                    lastTapAt = now
                                    val stamp = now
                                    val r = Runnable {
                                        if (lastTapAt == stamp) { lastTapAt = 0L; showMacroEditor(macro) }
                                    }
                                    pendingSingle = r; main.postDelayed(r, DOUBLE_TAP_MS)
                                }
                            }
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun startMacro(macro: TouchMacro, repeat: Boolean, source: View?): Boolean = runCatching {
        macroRunner.start(macro, repeat); runningMacroId = macro.id
        source?.background = triggerBackground(uiMode == UiMode.MACROS && editingMacroId == macro.id, true); true
    }.getOrElse {
        source?.background = rounded(Color.argb(205, 204, 66, 63), macro.trigger.sizeDp / 2f)
        source?.postDelayed({ source.background = triggerBackground(false, false) }, 550)
        runtimeStatus = "خطأ في الماكرو · ${it.message ?: it.javaClass.simpleName}"; false
    }

    // مدير التسجيل ---------------------------------------------------------------

    private fun showRecordingManager() {
        hideRecordingManager()
        if (recording || uiMode != UiMode.RECORDINGS) return
        val root = panelRoot()
        root.addView(panelHeader("تسجيل الإجراءات") { hideRecordingManager(); enterRuntimeMode() })

        val create = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labelInput = compactInput("", "اسم التسجيل الجديد")
        create.addView(labelInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(7) })
        create.addView(compactTextButton("بدء التسجيل") {
            startOverlayRecording(labelInput.text.toString().trim())
        }, LinearLayout.LayoutParams(dp(86), dp(40)))
        root.addView(create, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaxHeightScrollView(this).apply { maxHeightPx = dp(360); addView(list, ViewGroup.LayoutParams(-1, -2)) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        requestAsync("LIST") { response ->
            if (uiMode != UiMode.RECORDINGS || recordingManagerView == null) return@requestAsync
            val names = parseRecordingNames(response)
            list.removeAllViews()
            if (names.isEmpty()) list.addView(note("لا توجد إجراءات مسجلة بعد"))
            names.forEach { name -> list.addView(recordingManagerRow(name)) }
        }

        val width = minOf(dp(330), resources.displayMetrics.widthPixels - dp(20))
        val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { x = dp(10); y = dp(58) }
        routedWindows.addView(root, p); recordingManagerView = root
    }

    private fun recordingManagerRow(name: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(5), dp(5)); background = rounded(Color.argb(220, 255, 255, 255), 11f, Color.argb(90, 120, 126, 138))
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@OverlayService).apply { text = recordingLabel(name); textSize = 12.5f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(this@OverlayService).apply { text = name; textSize = 9.5f; setTextColor(muted) })
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(smallAction("تشغيل") { requestAsync("PLAY $name 1.0 1") {} })
        row.addView(smallAction("إعادة تسمية") { showNameEditor("اسم التسجيل", recordingLabel(name)) { setRecordingLabel(name, it); showRecordingManager() } })
        row.addView(smallAction("حذف", danger = true) {
            requestAsync("DELETE $name") { recordingLabels.edit().remove(name).apply(); showRecordingManager() }
        })
        return row.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(6) } }
    }

    private fun hideRecordingManager() {
        recordingManagerView?.let { runCatching { routedWindows.removeView(it) } }; recordingManagerView = null
    }

    // محرر الماكرو المرتبط بالمشغل -----------------------------------------------

    private fun showMacroEditor(macro: TouchMacro) {
        if (uiMode != UiMode.MACROS) return
        hideMacroManager(); hideMacroEditor(); hideRecordingPicker(); editingMacroId = macro.id
        if (selectedActionIndex != null && selectedActionIndex !in macro.actions.indices) selectedActionIndex = null
        syncTriggerButtons()

        val content = panelRoot()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val macroName = compactInput(macro.name, "اسم الماكرو")
        header.addView(macroName, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        header.addView(compactTextButton("حفظ") { macro.name = macroName.text.toString().trim().ifBlank { "ماكرو" }; saveProfiles(); showMacroEditor(macro) }, LinearLayout.LayoutParams(dp(56), dp(40)).apply { rightMargin = dp(5) })
        header.addView(compactTextButton("تم") { closeMacroEditor() }, LinearLayout.LayoutParams(dp(56), dp(40)))
        content.addView(header)

        val triggerSettings = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        triggerSettings.addView(TextView(this).apply { text = "المشغل"; textSize = 11f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(40), dp(38)))
        triggerSettings.addView(modeChoice("نقرة واحدة", macro.trigger.mode == MacroTriggerMode.CLICK_ONCE) { macro.trigger.mode = MacroTriggerMode.CLICK_ONCE; saveProfiles(); syncTriggerButtons(); showMacroEditor(macro) })
        triggerSettings.addView(modeChoice("ضغط مطول", macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) { macro.trigger.mode = MacroTriggerMode.HOLD_REPEAT; saveProfiles(); syncTriggerButtons(); showMacroEditor(macro) })
        triggerSettings.addView(smallAction("−") { resizeTrigger(macro, -4f) })
        triggerSettings.addView(TextView(this).apply { text = "${macro.trigger.sizeDp.toInt()}"; gravity = Gravity.CENTER; textSize = 10.5f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(34), dp(38)))
        triggerSettings.addView(smallAction("+") { resizeTrigger(macro, 4f) })
        content.addView(triggerSettings, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })

        content.addView(sectionLabel("الإجراءات · تنفيذ متسلسل"), LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(5) })
        val actionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        macro.actions.forEachIndexed { index, action -> actionList.addView(actionRow(macro, index, action)) }
        if (macro.actions.isEmpty()) actionList.addView(note("لا توجد إجراءات بعد"))
        content.addView(MaxHeightScrollView(this).apply { maxHeightPx = dp(300); addView(actionList, ViewGroup.LayoutParams(-1, -2)) })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(
            "+ نقرة" to { showTapPositionEditor(macro, null) },
            "+ سحب" to { showDragPositionEditor(macro, null) },
            "+ انتظار" to { macro.actions += MacroAction.Wait(500); selectedActionIndex = macro.actions.lastIndex; saveProfiles(); showMacroEditor(macro) },
            "+ تسجيل" to { showRecordingPicker(macro, null) },
        ).forEachIndexed { i, (label, action) ->
            addRow.addView(compactTextButton(label) { action() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i < 3) rightMargin = dp(5) })
        }
        content.addView(addRow, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(7) })

        selectedActionIndex?.let { index -> macro.actions.getOrNull(index)?.let { content.addView(actionDetail(macro, index, it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) } }
        content.addView(compactTextButton("حذف هذا الماكرو", danger = true) { deleteMacro(macro) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })

        val width = minOf(dp(350), resources.displayMetrics.widthPixels - dp(18))
        val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = MaxHeightScrollView(this).apply { maxHeightPx = maxH; addView(content, ViewGroup.LayoutParams(-1, -2)) }
        val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { x = dp(9); y = dp(52) }
        routedWindows.addView(scroll, p); editorView = scroll
    }

    private fun actionRow(macro: TouchMacro, index: Int, action: MacroAction): View {
        val selected = selectedActionIndex == index
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(4), dp(4), dp(4)); background = rounded(if (selected) Color.argb(205, 234, 241, 255) else Color.argb(210, 255, 255, 255), 10f, Color.argb(80, 120, 126, 138))
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OverlayService).apply { text = "${index + 1}. ${actionTitle(action)}"; textSize = 12f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(this@OverlayService).apply { text = actionSummary(action); textSize = 10f; setTextColor(muted) })
                setOnClickListener { selectedActionIndex = index; showMacroEditor(macro) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(smallAction("↑") { moveAction(macro, index, -1) })
            addView(smallAction("↓") { moveAction(macro, index, 1) })
            addView(smallAction("×", danger = true) { macro.actions.removeAt(index); selectedActionIndex = null; saveProfiles(); showMacroEditor(macro) })
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) } }
    }

    private fun actionDetail(macro: TouchMacro, index: Int, action: MacroAction): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(9)); background = rounded(Color.argb(220, 246, 248, 251), 11f, Color.argb(75, 120, 126, 138))
        addView(sectionLabel("تعديل ${index + 1}. ${actionTitle(action)}"))
        when (action) {
            is MacroAction.Tap -> {
                addView(parameterRow("مدة الضغط", "${action.holdMs} ms", { action.holdMs = (action.holdMs - 20).coerceAtLeast(20); saveProfiles(); showMacroEditor(macro) }, { action.holdMs = (action.holdMs + 20).coerceAtMost(5000); saveProfiles(); showMacroEditor(macro) }))
                addView(compactTextButton("تعيين موضع النقر بصرياً") { showTapPositionEditor(macro, index) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            }
            is MacroAction.Drag -> {
                addView(parameterRow("مدة السحب", "${action.durationMs} ms", { action.durationMs = (action.durationMs - 100).coerceAtLeast(50); saveProfiles(); showMacroEditor(macro) }, { action.durationMs = (action.durationMs + 100).coerceAtMost(30000); saveProfiles(); showMacroEditor(macro) }))
                addView(compactTextButton("تعيين نقطة البداية والنهاية والاتجاه بصرياً") { showDragPositionEditor(macro, index) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            }
            is MacroAction.Wait -> addView(waitDurationSlider(macro, action))
            is MacroAction.Recording -> {
                addView(parameterRow("سرعة التشغيل", "${trimSpeed(action.speed)} ×", { action.speed = (action.speed / 2f).coerceAtLeast(0.25f); saveProfiles(); showMacroEditor(macro) }, { action.speed = (action.speed * 2f).coerceAtMost(8f); saveProfiles(); showMacroEditor(macro) }))
                addView(compactTextButton("تغيير إجراء التسجيل · ${recordingLabel(action.recordingName)}") { showRecordingPicker(macro, index) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            }
        }
    }

    private fun waitDurationSlider(macro: TouchMacro, action: MacroAction.Wait): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val label = sectionLabel("وقت الانتظار · ${action.durationMs} ms")
        addView(label, LinearLayout.LayoutParams(-1, dp(32)))
        addView(SeekBar(this@OverlayService).apply {
            max = 1000
            progress = action.durationMs.coerceIn(0L, 1000L).toInt()
            keyProgressIncrement = 1
            contentDescription = "وقت الانتظار، من 0 إلى 1000 مللي ثانية"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    action.durationMs = value.toLong()
                    label.text = "وقت الانتظار · $value ms"
                    saveProfiles()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {
                    bar.parent.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    bar.parent.requestDisallowInterceptTouchEvent(false)
                    showMacroEditor(macro)
                }
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun hideMacroEditor() { editorView?.let { runCatching { routedWindows.removeView(it) } }; editorView = null }
    private fun closeMacroEditor() { hideMacroEditor(); hideRecordingPicker(); editingMacroId = null; selectedActionIndex = null; syncTriggerButtons() }
    private fun resizeTrigger(macro: TouchMacro, delta: Float) { macro.trigger.sizeDp = (macro.trigger.sizeDp + delta).coerceIn(28f, 96f); saveProfiles(); syncTriggerButtons(); showMacroEditor(macro) }
    private fun deleteMacro(macro: TouchMacro) {
        if (runningMacroId == macro.id) macroRunner.stop(); activeProfile().macros.removeAll { it.id == macro.id }; saveProfiles()
        editingMacroId = null; selectedActionIndex = null; hideMacroEditor(); hideRecordingPicker(); syncTriggerButtons()
    }
    private fun moveAction(macro: TouchMacro, index: Int, delta: Int) {
        if (macro.actions.isEmpty()) return; val to = (index + delta).coerceIn(0, macro.actions.lastIndex); if (to == index) return
        val value = macro.actions.removeAt(index); macro.actions.add(to, value); selectedActionIndex = to; saveProfiles(); showMacroEditor(macro)
    }

    // منتقي إجراء التسجيل -------------------------------------------------------

    private fun showRecordingPicker(macro: TouchMacro, replaceIndex: Int?) {
        hideRecordingPicker()
        requestAsync("LIST") { response ->
            if (editingMacroId != macro.id) return@requestAsync
            val names = parseRecordingNames(response)
            val root = panelRoot(); root.addView(panelHeader("اختيار إجراء التسجيل") { hideRecordingPicker() })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.forEach { name ->
                list.addView(compactTextButton(recordingLabel(name)) {
                    val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Recording }
                    val value = MacroAction.Recording(name, old?.speed ?: 1f)
                    if (replaceIndex == null) { macro.actions += value; selectedActionIndex = macro.actions.lastIndex } else { macro.actions[replaceIndex] = value; selectedActionIndex = replaceIndex }
                    saveProfiles(); hideRecordingPicker(); showMacroEditor(macro)
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(5) })
            }
            if (names.isEmpty()) list.addView(note("لا توجد إجراءات تسجيل، يرجى إنشاؤها أولاً في وضع التسجيل"))
            root.addView(MaxHeightScrollView(this).apply { maxHeightPx = dp(320); addView(list, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(24))
            val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {}
            routedWindows.addView(root, p); recordingPickerView = root
        }
    }

    private fun hideRecordingPicker() { recordingPickerView?.let { runCatching { routedWindows.removeView(it) } }; recordingPickerView = null }

    private fun showNameEditor(title: String, initial: String, onSave: (String) -> Unit) {
        hideNameEditor()
        val root = panelRoot(); root.addView(sectionLabel(title))
        val input = compactInput(initial, title)
        root.addView(input, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(compactTextButton("إلغاء") { hideNameEditor() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        actions.addView(compactTextButton("حفظ") { val value = input.text.toString().trim(); if (value.isNotEmpty()) onSave(value); hideNameEditor() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(7) })
        val p = focusableOverlayParams(minOf(dp(300), resources.displayMetrics.widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        routedWindows.addView(root, p); nameEditorView = root
    }

    private fun hideNameEditor() { nameEditorView?.let { runCatching { routedWindows.removeView(it) } }; nameEditorView = null }

    // محرر موضع النقر/السحب المرئي ---------------------------------------------

    private fun showTapPositionEditor(macro: TouchMacro, replaceIndex: Int?) {
        val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Tap }
        val start = (old?.x ?: 0.5f) to (old?.y ?: 0.5f)
        val hold = old?.holdMs ?: 70L
        showPositionEditor("نقرة", start, null) { a, _ ->
            val value = MacroAction.Tap(a.first, a.second, hold)
            if (replaceIndex == null) {
                macro.actions += value
                selectedActionIndex = macro.actions.lastIndex
            } else {
                macro.actions[replaceIndex] = value
                selectedActionIndex = replaceIndex
            }
            saveProfiles()
        }
    }

    private fun showDragPositionEditor(macro: TouchMacro, replaceIndex: Int?) {
        val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Drag }
        val start = (old?.x1 ?: 0.35f) to (old?.y1 ?: 0.55f)
        val end = (old?.x2 ?: 0.65f) to (old?.y2 ?: 0.55f)
        val duration = old?.durationMs ?: 450L
        showPositionEditor("سحب", start, end) { a, b ->
            val e = b ?: return@showPositionEditor
            val value = MacroAction.Drag(a.first, a.second, e.first, e.second, duration)
            if (replaceIndex == null) {
                macro.actions += value
                selectedActionIndex = macro.actions.lastIndex
            } else {
                macro.actions[replaceIndex] = value
                selectedActionIndex = replaceIndex
            }
            saveProfiles()
        }
    }

    private fun showPositionEditor(
        title: String,
        start: Pair<Float, Float>,
        end: Pair<Float, Float>?,
        onConfirm: (Pair<Float, Float>, Pair<Float, Float>?) -> Unit,
    ) {
        hideMacroEditor(); hideRecordingPicker(); hideMacroManager(); hideMainMenu(); hideTriggerButtons(); removePositionEditor()
        val (screenW, screenH) = screenGeometry.size()
        val startGlobal = screenGeometry.toGlobal(start.first, start.second)
        val endGlobal = screenGeometry.toGlobal(end?.first ?: start.first, end?.second ?: start.second)
        var globalStartX = startGlobal.x
        var globalStartY = startGlobal.y
        var globalEndX = endGlobal.x
        var globalEndY = endGlobal.y

        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(12, 0, 0, 0)) }
        val guide = MacroGeometryGuideView(this).apply { arrowEnabled = end != null }
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))

        val handleSize = dp(46)
        fun makeHandle(label: String) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); background = rounded(Color.argb(205, 52, 103, 215), 23f)
            elevation = dp(7).toFloat()
        }
        val startHandle: View = if (end == null) View(this).apply {
            contentDescription = "اسحب هدف النقر"
        } else makeHandle("بداية")
        val endHandle = if (end != null) makeHandle("نهاية") else null
        root.addView(startHandle, FrameLayout.LayoutParams(handleSize, handleSize))
        if (endHandle != null) root.addView(endHandle, FrameLayout.LayoutParams(handleSize, handleSize))

        fun origin(): LogicalScreenGeometry.Point =
            screenGeometry.globalOrigin(root) ?: LogicalScreenGeometry.Point(0f, 0f)
        fun update() {
            val (ox, oy) = origin()
            val sx = globalStartX - ox; val sy = globalStartY - oy
            val ex = globalEndX - ox; val ey = globalEndY - oy
            guide.x1 = sx; guide.y1 = sy; guide.x2 = ex; guide.y2 = ey
            (startHandle.layoutParams as FrameLayout.LayoutParams).also {
                it.leftMargin = (sx - handleSize / 2f).toInt().coerceIn(0, (root.width - handleSize).coerceAtLeast(0))
                it.topMargin = (sy - handleSize / 2f).toInt().coerceIn(0, (root.height - handleSize).coerceAtLeast(0))
                startHandle.layoutParams = it
            }
            if (endHandle != null) (endHandle.layoutParams as FrameLayout.LayoutParams).also {
                it.leftMargin = (ex - handleSize / 2f).toInt().coerceIn(0, (root.width - handleSize).coerceAtLeast(0))
                it.topMargin = (ey - handleSize / 2f).toInt().coerceIn(0, (root.height - handleSize).coerceAtLeast(0))
                endHandle.layoutParams = it
            }
        }
        fun installDrag(handle: View, isStart: Boolean) {
            var downX = 0f; var downY = 0f; var initialX = 0f; var initialY = 0f
            handle.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        initialX = if (isStart) globalStartX else globalEndX
                        initialY = if (isStart) globalStartY else globalEndY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val gx = (initialX + e.rawX - downX).coerceIn(0f, screenW.toFloat())
                        val gy = (initialY + e.rawY - downY).coerceIn(0f, screenH.toFloat())
                        if (isStart) { globalStartX = gx; globalStartY = gy } else { globalEndX = gx; globalEndY = gy }
                        update(); true
                    }
                    else -> true
                }
            }
        }
        installDrag(startHandle, true); if (endHandle != null) installDrag(endHandle, false)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(pill(title, true, false) {})
            addView(pill("إلغاء", false, false) {
                removePositionEditor(); syncTriggerButtons(); editingMacroId?.let { findMacro(it)?.let(::showMacroEditor) }
            })
            addView(pill("تأكيد", false, false) {
                val startNormalized = screenGeometry.toNormalized(globalStartX, globalStartY)
                val endNormalized = if (end != null) screenGeometry.toNormalized(globalEndX, globalEndY) else null
                val a = startNormalized.x to startNormalized.y
                val b = endNormalized?.let { it.x to it.y }
                onConfirm(a, b)
                removePositionEditor(); syncTriggerButtons(); editingMacroId?.let { findMacro(it)?.let(::showMacroEditor) }
            })
        }
        root.addView(controls, FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })

        val params = overlayParams(-1, -1, Gravity.TOP or Gravity.START).apply {
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        routedWindows.addView(root, params)
        positionEditorView = root
        root.post { update() }
    }

    private fun removePositionEditor() {
        positionEditorView?.let { runCatching { routedWindows.removeView(it) } }
        positionEditorView = null
    }

    // التسجيل -------------------------------------------------------------------

    private fun startOverlayRecording(displayName: String = "") {
        if (!client.isConnected) { connectRuntime(); return }
        hideMainMenu(); hideRecordingManager(); hideMacroManager(); hideMacroEditor(); hideRecordingPicker(); hideNameEditor(); hideTriggerButtons(); hideEntry()
        val name = "rec_" + SimpleDateFormat("HHmmss", Locale.US).format(Date())
        setRecordingLabel(name, displayName.ifBlank { "تسجيل " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) })
        val width = dp(94); val height = dp(34); val right = dp(10); val top = dp(22)
        val view = TextView(this).apply {
            text = "■ إيقاف"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD; background = rounded(Color.argb(198, 195, 57, 55), 17f)
            setOnClickListener { if (recording) stopOverlayRecording(name) }
        }
        val params = overlayParams(width, height, Gravity.TOP or Gravity.END).apply { x = right; y = top }
        routedWindows.addView(view, params)
        recordingStopView = view
        view.post {
            screenGeometry.globalBounds(view)?.let { bounds ->
                requestAsync("RECORD_EXCLUDE ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}") {
                    requestAsync("RECORD_START $name") {
                        recording = true
                        activeRecordingName = name
                    }
                }
            }
        }
    }

    private fun stopOverlayRecording(name: String) {
        requestAsync("RECORD_STOP") {
            recording = false; activeRecordingName = null
            requestAsync("RECORD_EXCLUDE_CLEAR") {}
            removeRecordingStop(); showEntry(); syncTriggerButtons()
            if (uiMode == UiMode.RECORDINGS) showRecordingManager()
        }
    }

    private fun repositionRecordingStop() {
        val name = activeRecordingName ?: return
        removeRecordingStop()
        val width = dp(94); val height = dp(34); val right = dp(10); val top = dp(22)
        val view = TextView(this).apply {
            text = "■ إيقاف"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD; background = rounded(Color.argb(198, 195, 57, 55), 17f)
            setOnClickListener { stopOverlayRecording(name) }
        }
        routedWindows.addView(view, overlayParams(width, height, Gravity.TOP or Gravity.END).apply { x = right; y = top })
        recordingStopView = view
        view.post {
            screenGeometry.globalBounds(view)?.let { bounds ->
                requestAsync("RECORD_EXCLUDE ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}") {}
            }
        }
    }

    private fun removeRecordingStop() {
        recordingStopView?.let { runCatching { routedWindows.removeView(it) } }
        recordingStopView = null
    }

    // الاستمرارية / البحث -------------------------------------------------------

    private fun findMacro(id: String): TouchMacro? = profiles.asSequence().flatMap { it.macros.asSequence() }.firstOrNull { it.id == id }

    private fun saveProfiles() = macroStore.saveProfiles(profiles)

    private fun parseRecordingNames(response: String): List<String> =
        response.removePrefix("RECORDINGS").trim().let { if (it.isBlank()) emptyList() else it.split(',').filter(String::isNotBlank) }

    private fun recordingLabel(name: String): String = recordingLabels.getString(name, null)?.takeIf { it.isNotBlank() } ?: name
    private fun setRecordingLabel(name: String, label: String) { recordingLabels.edit().putString(name, label.trim().ifBlank { name }).apply() }

    private fun cancelRawTriggerHolds() {
        if (rawTriggerHoldStarted.isNotEmpty()) macroRunner.stop()
        rawTriggerHoldStarted.clear()
    }

    private fun syncRuntimeTriggerRegions(generation: Long = triggerGeometryGeneration) {
        if (generation != triggerGeometryGeneration) return
        pushRuntimeTriggerRegions(emptyList())
        routedWindows.sync()
    }

    private data class RuntimeTriggerRegion(
        val id: String,
        val bounds: LogicalScreenGeometry.Rect,
    )

    private fun pushRuntimeTriggerRegions(regions: List<RuntimeTriggerRegion>) {
        val revision = ++triggerRegionRevision
        val command = buildString {
            append("TRIGGER_SET ").append(regions.size)
            regions.forEach { region ->
                append(' ').append(region.id)
                append(' ').append(region.bounds.left)
                append(' ').append(region.bounds.top)
                append(' ').append(region.bounds.right)
                append(' ').append(region.bounds.bottom)
            }
        }
        io.execute {
            if (revision != triggerRegionRevision) return@execute
            runCatching {
                if (!client.isConnected) client.connect()
                if (revision != triggerRegionRevision) return@runCatching
                client.request(command)
            }.onFailure { t ->
                if (revision == triggerRegionRevision) {
                    main.post { runtimeStatus = "فشل مزامنة المشغلات · ${t.message ?: t.javaClass.simpleName}" }
                }
            }
        }
    }

    private fun requestStatus() = requestAsync("STATUS") { runtimeStatus = it }

    private fun requestAsync(command: String, onSuccess: (String) -> Unit) {
        io.execute {
            runCatching {
                if (!client.isConnected) client.connect()
                client.request(command)
            }.onSuccess { result -> main.post { onSuccess(result) } }
                .onFailure { t -> main.post { runtimeStatus = "خطأ · ${t.message ?: t.javaClass.simpleName}" } }
        }
    }

    private fun removeAllWindows() {
        hideMainMenu(); hideRecordingManager(); hideMacroManager(); hideMacroEditor(); hideRecordingPicker(); hideNameEditor(); hideTriggerButtons()
        removeRecordingStop(); removePositionEditor(); hideEntry()
    }

    private fun panelRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = rounded(Color.argb(236, 248, 249, 251), 16f, Color.argb(100, 112, 118, 128))
        elevation = dp(10).toFloat()
    }

    private fun panelHeader(title: String, close: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@OverlayService).apply { text = title; textSize = 15f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(compactTextButton("إغلاق") { close() }, LinearLayout.LayoutParams(dp(58), dp(36)))
    }

    private fun compactInput(value: String, hintValue: String) = EditText(this).apply {
        setText(value); hint = hintValue; textSize = 13f; setTextColor(ink); setHintTextColor(Color.argb(145, 100, 106, 116))
        isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT; setPadding(dp(10), 0, dp(10), 0)
        background = rounded(Color.argb(220, 255, 255, 255), 10f, Color.argb(95, 115, 121, 132))
        setSelectAllOnFocus(false)
    }

    private fun compactTextButton(label: String, danger: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (danger) red else accent)
        background = rounded(Color.argb(190, 255, 255, 255), 10f, Color.argb(85, 120, 126, 138))
        setOnClickListener { click() }
    }

    private fun smallAction(label: String, danger: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 10.5f; setTextColor(if (danger) red else accent); setPadding(dp(7), 0, dp(7), 0); setOnClickListener { click() }
    }

    private fun modeChoice(label: String, active: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 10.5f; setTextColor(if (active) Color.WHITE else ink)
        background = rounded(if (active) Color.argb(190, 52, 103, 215) else Color.argb(175, 255, 255, 255), 9f, Color.argb(70, 120, 126, 138))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(36)).apply { rightMargin = dp(5) }
    }

    private fun sectionLabel(value: String) = TextView(this).apply { text = value; textSize = 11.5f; setTextColor(muted); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL }

    private fun note(value: String) = TextView(this).apply { text = value; textSize = 11f; setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8)) }

    private fun parameterRow(label: String, value: String, minus: () -> Unit, plus: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@OverlayService).apply { text = label; textSize = 11.5f; setTextColor(ink) }, LinearLayout.LayoutParams(0, dp(38), 1f))
        addView(smallActionView("−", minus))
        addView(TextView(this@OverlayService).apply { text = value; gravity = Gravity.CENTER; textSize = 11f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(84), dp(38)))
        addView(smallActionView("+", plus))
    }

    private fun smallActionView(label: String, click: () -> Unit) = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 15f; setTextColor(accent); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) }

    private fun actionTitle(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "نقرة"
        is MacroAction.Drag -> "سحب"
        is MacroAction.Wait -> "انتظار"
        is MacroAction.Recording -> "تشغيل تسجيل"
    }

    private fun actionSummary(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "الموضع ${(action.x * 100).toInt()}%, ${(action.y * 100).toInt()}% · ${action.holdMs}ms"
        is MacroAction.Drag -> "${action.durationMs}ms · ${(action.x1 * 100).toInt()}%,${(action.y1 * 100).toInt()}% ← ${(action.x2 * 100).toInt()}%,${(action.y2 * 100).toInt()}%"
        is MacroAction.Wait -> "${action.durationMs}ms"
        is MacroAction.Recording -> "${recordingLabel(action.recordingName)} · ${trimSpeed(action.speed)}×"
    }

    private fun focusableOverlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    // عناصر واجهة صغيرة ---------------------------------------------------------

    private fun bubble(label: String, fill: Int, radius: Float, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); background = rounded(fill, radius); elevation = dp(5).toFloat()
        setOnClickListener { click() }
    }

    private fun pill(label: String, active: Boolean, compact: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = if (compact) 11f else 10.8f
        typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (active) Color.WHITE else ink)
        setPadding(dp(if (compact) 7 else 10), 0, dp(if (compact) 7 else 10), 0)
        background = rounded(
            if (active) Color.argb(190, 52, 103, 215) else Color.argb(172, 245, 247, 250),
            18f,
            if (active) null else Color.argb(110, 120, 126, 138),
        )
        setOnClickListener { click() }
    }

    private fun actionShort(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "ن"
        is MacroAction.Drag -> "س"
        is MacroAction.Wait -> "ا"
        is MacroAction.Recording -> "ت"
    }

    private fun trimSpeed(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun triggerBackground(editing: Boolean, running: Boolean): GradientDrawable = when {
        running -> rounded(Color.argb(205, 44, 151, 99), 48f)
        editing -> rounded(Color.argb(205, 52, 103, 215), 48f, Color.argb(230, 255, 255, 255))
        else -> rounded(Color.argb(172, 52, 103, 215), 48f)
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
