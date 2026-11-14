package dev.touchcompositor.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import dev.touchcompositor.shell.RuntimeBuild
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** اتصال Shizuku مشترك على مستوى العملية بين النشاط والأدوات العائمة. */
class RuntimeEnvironment(context: Context) : AutoCloseable {
    companion object {
        private val lock = Any()
        @Volatile private var service: ITouchService? = null
        private var binding: ServiceConnection? = null
        val available: Boolean get() = Shizuku.pingBinder()
        val authorized: Boolean get() = available && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
    data class Ready(val readyLine: String, val launchedNow: Boolean)
    private val app = context.applicationContext
    private val args get() = Shizuku.UserServiceArgs(ComponentName(app, ShizukuTouchService::class.java))
        .daemon(false).processNameSuffix("touch_service").version(RuntimeBuild.BUILD)

    fun ensureReady(): Result<Ready> = runCatching {
        check(Looper.myLooper() != Looper.getMainLooper()) { "يرجى تشغيل البيئة في الخلفية" }
        synchronized(lock) {
            check(available) { "يرجى تشغيل الخدمة في Shizuku أولاً" }
            check(authorized) { "يرجى منح Touch Compositor إذن استخدام Shizuku" }
            check(Shizuku.getVersion() >= 13) { "يرجى تحديث Shizuku إلى الإصدار v13 أو أحدث" }
            val remote = connectService()
            val owner = remote.identity()
            checkExistingRuntime(owner)?.let { return@synchronized Ready(it, false) }
            remote.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline) {
                checkExistingRuntime(owner)?.let { return@synchronized Ready(it, true) }
                val state = remote.status()
                check(!state.startsWith("failed:") && state != "stopped") { state }
                Thread.sleep(100)
            }
            error("انتهت مهلة بدء خدمة اللمس: ${remote.status()}")
        }
    }

    fun restart(): Result<Ready> = runCatching {
        synchronized(lock) {
            RuntimeClient().use { client ->
                runCatching { client.connect(450); client.request("SHUTDOWN", 1000) }
            }
            val remote = service
            if (remote != null) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                while (remote.status().let { it != "stopped" && !it.startsWith("failed:") && it != "idle" }) {
                    check(System.nanoTime() < deadline) { "خدمة اللمس القديمة لم تتوقف بعد، يرجى إعادة تشغيل الخدمة في Shizuku" }
                    Thread.sleep(100)
                }
            }
            ensureReady().getOrThrow()
        }
    }

    private fun connectService(): ITouchService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        binding?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
        val latch = CountDownLatch(1)
        var connected: ITouchService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected = ITouchService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        binding = connection
        try {
            Shizuku.bindUserService(args, connection)
            check(latch.await(10, TimeUnit.SECONDS)) { "انتهت مهلة اتصال خدمة Shizuku، يرجى المحاولة مرة أخرى" }
            return checkNotNull(connected).also { service = it }
        } catch (t: Throwable) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            binding = null
            throw t
        }
    }

    private fun checkExistingRuntime(owner: String): String? = RuntimeClient().use { client ->
        runCatching {
            val ready = client.connect(400)
            if (ready.startsWith("READY touch-runtime-v1 ") && ready.split(' ').containsAll(listOf("build=${RuntimeBuild.BUILD}", "owner=$owner"))) ready
            else { client.request("SHUTDOWN", 900); Thread.sleep(250); null }
        }.getOrNull()
    }

    override fun close() = Unit
}
