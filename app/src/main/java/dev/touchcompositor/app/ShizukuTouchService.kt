package dev.touchcompositor.app

import android.content.Context
import android.os.Build
import android.system.Os
import dev.touchcompositor.shell.DeviceProbe
import dev.touchcompositor.shell.LiveTouchEngine
import dev.touchcompositor.shell.TouchRuntimeServer
import java.io.File
import java.util.concurrent.TimeUnit

/** يتم إنشاؤه في عملية Shizuku UserService ذات الصلاحيات، وليس في عملية الواجهة. */
class ShizukuTouchService(private val context: Context) : ITouchService.Stub() {
    private val sessionId = java.util.UUID.randomUUID().toString()
    override fun identity(): String = sessionId
    @Volatile private var worker: Thread? = null
    @Volatile private var server: TouchRuntimeServer? = null
    @Volatile private var state = "idle"

    @Synchronized override fun start(): String {
        if (worker?.isAlive == true) return state
        val touch = DeviceProbe.parseTouchscreen(command("/system/bin/getevent", "-pl"))
            ?: error("لم يتم العثور على جهاز لمس متعدد قابل للقراءة")
        val size = DeviceProbe.parseWmSize(command("wm", "size")) ?: error("تعذر قراءة أبعاد الشاشة")
        val rotation = DeviceProbe.parseRotation(command("dumpsys", "input").lineSequence()
            .firstOrNull { "Viewport INTERNAL" in it }.orEmpty())
        val asset = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "tc_evgrab_arm64"
            "armeabi-v7a", "armeabi" -> "tc_evgrab_armv7"
            "x86_64" -> "tc_evgrab_x86_64"
            "x86" -> "tc_evgrab_x86"
            else -> error("بنية الجهاز غير مدعومة حالياً")
        }
        val helper = File.createTempFile("tc_evgrab_", "", File("/data/local/tmp"))
        try {
            context.assets.open(asset).use { input -> helper.outputStream().use(input::copyTo) }
            Os.chmod(helper.path, 448)
        } catch (t: Throwable) {
            helper.delete()
            throw t
        }
        state = "starting uid=${Os.getuid()}"
        worker = Thread({
            try {
                val engine = LiveTouchEngine(touch.devicePath, touch.rawMinX, touch.rawMaxX,
                    touch.rawMinY, touch.rawMaxY, size.first, size.second, rotation, helper.path,
                    hasHardwarePressure = touch.hasPressure)
                val runtime = TouchRuntimeServer(engine, size.first, size.second, rotation, sessionId)
                server = runtime
                runtime.runTcp()
                state = "stopped"
            } catch (t: Throwable) {
                state = "failed: ${t.message ?: t.javaClass.simpleName}"
                android.util.Log.e("ShizukuTouchService", state, t)
            } finally {
                server = null
                helper.delete()
            }
        }, "shizuku-touch-runtime").apply { start() }
        return state
    }

    override fun status(): String = state
    @Synchronized override fun destroy() {
        server?.shutdown()
        worker?.join(2000)
        kotlin.system.exitProcess(0)
    }

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = StringBuilder()
        val drain = Thread { process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { synchronized(output) { output.append(it).append('\n') } }
        } }.apply { start() }
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "انتهت مهلة فحص الجهاز: ${args.first()}" }
            drain.join(1000)
            check(process.exitValue() == 0) { "فشل فحص الجهاز: ${args.first()}" }
            return synchronized(output) { output.toString() }
        } finally { process.destroy(); process.inputStream.close() }
    }
}
