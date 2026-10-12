package dev.omalauncher.linux

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opt-in GPU acceleration for Linux apps. The device node picks the driver:
 * PanVK-kbase on /dev/mali0 (Mali v10+: Pixel 7 and newer, recent MediaTek),
 * or Turnip on /dev/kgsl-3d0 (Adreno). Arch's stock Mesa runs OpenGL on top
 * with zink. Frames still reach us as wl_shm buffers (MESA_VK_WSI_DEBUG=sw),
 * so the compositor doesn't change.
 *
 * Each driver is a prerelease and talks to a kernel interface that Android
 * updates can change, so it is only used after a check passes, and checked
 * again after every system update; until then apps render in software.
 */
class Gpu(
    context: Context,
    private val proot: Proot,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** The pinned driver for this phone, or null when we have none. */
    private val spec = gpuKind(File("/dev/mali0").exists(), File("/dev/kgsl-3d0").exists())?.let(GpuDrivers::of)

    /** The phone has a GPU we have a driver for. */
    val supported = spec != null

    /** "Mali" or "Turnip", for the settings row. Empty when [supported] is false. */
    val driverLabel: String get() = spec?.label ?: ""

    /** The user turned it on; it may still be inactive until [recheck] passes. */
    var wanted by mutableStateOf(prefs.getBoolean(WANTED, false))
        private set

    /** Linux apps started from now on render on the GPU. */
    var active by mutableStateOf(false)
        private set

    /** The GPU the last check found, e.g. "Mali-G715 MC7". */
    var device by mutableStateOf(prefs.getString(DEVICE, null))
        private set

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    private val driverFile get() = spec?.let { File(proot.rootfs, it.driverPath.removePrefix("/")) }

    /** What changes under the driver: the kernel and Android's own GPU driver come with system updates. */
    private val build get() = Build.FINGERPRINT + " " + System.getProperty("os.version")

    /**
     * Activates the GPU if it passed its check on this system build. After
     * a system update, rechecks it first (in the background: apps render in
     * software meanwhile) and stays off if the check fails.
     */
    suspend fun recheck() {
        if (!supported || !wanted || active || state is JobState.Working) return
        val installed = driverFile ?: return turnOff()
        if (!installed.isFile) return turnOff()
        if (prefs.getString(CHECKED_BUILD, null) == build) return activate()
        Log.i(TAG, "system changed since the last GPU check, checking again")
        if (run("checking", probeScript())) activate()
    }

    /** Installs the driver and Mesa, then checks them; turns on only if the check passes. */
    suspend fun turnOn() {
        if (!supported || state is JobState.Working) return
        prefs.edit().putBoolean(WANTED, true).apply()
        wanted = true
        if (run("installing", installScript() + " && " + probeScript())) {
            activate()
        } else {
            // Nothing to come back to on the next start; the failure stays on screen.
            prefs.edit().putBoolean(WANTED, false).apply()
            wanted = false
        }
    }

    /** Back to software rendering for apps started from now on. The driver stays, so turning on again is quick. */
    fun turnOff() {
        prefs.edit().putBoolean(WANTED, false).remove(CHECKED_BUILD).apply()
        wanted = false
        active = false
        state = JobState.Idle
        proot.graphicsEnv = SOFTWARE_ENV
    }

    private fun activate() {
        val chosen = spec ?: return
        active = true
        proot.graphicsEnv = chosen.env
    }

    /** Runs [script]; on success remembers the build it passed on and the GPU's name from vulkaninfo. */
    private suspend fun run(
        action: String,
        script: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            state = JobState.Working(action, emptyList())
            val (ok, tail) = proot.runJob(script) { state = JobState.Working(action, it) }
            val name = tail.toList().firstNotNullOfOrNull { DEVICE_NAME.find(it)?.groupValues?.get(1) }
            Log.i(TAG, "$action: ${if (ok) "ok, $name" else "failed"}")
            if (ok) {
                device = name
                prefs.edit().putString(CHECKED_BUILD, build).putString(DEVICE, name).apply()
                state = JobState.Idle
            } else {
                state = JobState.Failed(tail.toList())
            }
            ok
        }

    /** Mesa (zink), the Vulkan loader and the probe tools from pacman; the pinned driver from its release, verified, unless it's already there. */
    private fun installScript() = spec?.let(GpuDrivers::installScript) ?: "false"

    /** Vulkan finds the GPU through the driver, and OpenGL runs on it through zink: offscreen, no window needed. */
    private fun probeScript() = spec?.let(GpuDrivers::probeScript) ?: "false"

    companion object {
        private const val TAG = "gpu"
        private const val WANTED = "gpu_wanted"
        private const val CHECKED_BUILD = "gpu_checked_build"
        private const val DEVICE = "gpu_device"

        private val DEVICE_NAME = Regex("""deviceName\s*=\s*(.+)""")

        /** Today's default: Mesa renders on the CPU (llvmpipe). */
        val SOFTWARE_ENV = listOf("LIBGL_ALWAYS_SOFTWARE=1")
    }
}
