package dev.omalauncher.linux

/**
 * The pinned Vulkan drivers [Gpu] can install. Detection and the guest
 * scripts live here, with no Android types, so they can be tested on the JVM.
 *
 * Mali keeps the PanVK-kbase blob. Adreno 6xx and newer get Turnip built for
 * KGSL, which is what Android exposes at /dev/kgsl-3d0; Arch's own Mesa talks
 * to DRM and would not see that node. Older Adreno GPUs are not offered:
 * Turnip cannot pass its probe there, and the install would still download.
 */
internal enum class GpuKind { MALI, TURNIP }

/** Where the .so comes from. The two shapes do not share a download path. */
internal sealed interface DriverPayload {
    /** The URL is the library itself. */
    data class RawLibrary(val sha256: String) : DriverPayload

    /** The URL is an Arch package; [member] is the library inside it. */
    data class ArchPackage(
        val packageSha256: String,
        val member: String,
        val librarySha256: String,
    ) : DriverPayload
}

internal data class GpuDriver(
    val kind: GpuKind,
    /** What vulkaninfo must print before the driver is trusted. */
    val probeId: String,
    /** Short name for the settings row and the probe's failure line. Vendor names, so the row matches the README. */
    val label: String,
    val packages: List<String>,
    val url: String,
    val payload: DriverPayload,
    val driverPath: String,
    /** Under /usr/local, which pacman does not own. The loader still finds it while the file exists. */
    val icdPath: String,
    val apiVersion: String,
) {
    /**
     * Vulkan through this ICD, presented as wl_shm; OpenGL is zink on top of it.
     * One app can still opt out with `GALLIUM_DRIVER=llvmpipe app`.
     */
    val env: List<String> =
        listOf(
            "VK_ICD_FILENAMES=$icdPath",
            "VK_DRIVER_FILES=$icdPath",
            "MESA_VK_WSI_DEBUG=sw",
            "GALLIUM_DRIVER=zink",
        )

    val librarySha256: String
        get() = when (val payload = payload) {
            is DriverPayload.RawLibrary -> payload.sha256
            is DriverPayload.ArchPackage -> payload.librarySha256
        }
}

/**
 * Software rendering. VK_ICD_FILENAMES and VK_DRIVER_FILES point at a file
 * that is never created, so the loader does not scan the default ICD directories
 * and cannot pick up a driver that was left behind.
 */
internal val SOFTWARE_ENV = listOf(
    "LIBGL_ALWAYS_SOFTWARE=1",
    "VK_ICD_FILENAMES=/usr/local/share/vulkan/icd.d/omalauncher-software.json",
    "VK_DRIVER_FILES=/usr/local/share/vulkan/icd.d/omalauncher-software.json",
)

/**
 * Mali when /dev/mali0 exists, else Turnip when /dev/kgsl-3d0 exists and
 * [kgslModel] is an Adreno 6xx or newer. A phone has one of these. Mali wins
 * if a build exposes both, so a Mali phone keeps the driver it has always had.
 * An unreadable Adreno model is not offered.
 */
internal fun gpuKind(
    hasMali: Boolean,
    hasKgsl: Boolean,
    kgslModel: String? = null,
): GpuKind? = when {
    hasMali -> GpuKind.MALI
    hasKgsl && turnipSupports(kgslModel) -> GpuKind.TURNIP
    else -> null
}

/** The three-digit Adreno model in a kgsl `gpu_model` string, or null when it is not there. */
internal fun adrenoModelNumber(model: String?): Int? {
    if (model.isNullOrBlank()) return null
    val named = Regex("(?i)adreno\\D*(\\d{3})").find(model)
    val token = named ?: Regex("(?<!\\d)(\\d{3})(?!\\d)").find(model.trim())
    return token?.groupValues?.get(1)?.toIntOrNull()
}

/** Turnip in this pin speaks Adreno 6xx and newer. */
internal fun turnipSupports(model: String?): Boolean {
    val number = adrenoModelNumber(model) ?: return false
    return number >= 600
}

internal object GpuDrivers {
    private val basePackages = listOf("mesa", "vulkan-icd-loader", "vulkan-tools", "mesa-utils")

    /** Removed on the way out. A previous install wrote Mali's manifest on pacman's path. */
    const val LEGACY_MALI_ICD = "/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json"

    private val mali = GpuDriver(
        kind = GpuKind.MALI,
        probeId = "DRIVER_ID_MESA_PANVK",
        label = "Mali",
        packages = basePackages,
        // PanVK-kbase 0.1.0 rc2, the glibc build. Tested upstream on a Pixel 9a (Mali-G715).
        url = "https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/panvk-kbase-v0.1.0-rc2/libvulkan_panfrost-glibc-aarch64.so",
        payload = DriverPayload.RawLibrary(
            sha256 = "0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820",
        ),
        driverPath = "/usr/local/lib64/libvulkan_panfrost.so",
        icdPath = "/usr/local/share/vulkan/icd.d/panfrost_icd.aarch64.json",
        apiVersion = "1.4.363",
    )

    private val turnip = GpuDriver(
        kind = GpuKind.TURNIP,
        probeId = "DRIVER_ID_MESA_TURNIP",
        label = "Adreno",
        // The .so needs libSPIRV-Tools.so and libxcb-keysyms.so.1, which mesa does not always pull in.
        packages = basePackages + listOf("spirv-tools", "xcb-util-keysyms"),
        // lfdevs turnip-26.3.0-devel-20260824, the Arch aarch64 vulkan-freedreno package (KGSL, glibc).
        url = "https://github.com/lfdevs/mesa-for-android-container/releases/download/turnip-26.3.0-devel-20260824/vulkan-freedreno-1-26.3.0-1-aarch64.pkg.tar.xz",
        payload = DriverPayload.ArchPackage(
            packageSha256 = "9824514d7180402d936f83f4ad422f338b7df31ff2804eab8a0badb13ee44a77",
            member = "usr/lib/libvulkan_freedreno.so",
            librarySha256 = "d7e22a8be00e94482a0bfc7b769f4c7c9b03573532f3257c42242d1502e9f69f",
        ),
        driverPath = "/usr/local/lib64/libvulkan_freedreno.so",
        icdPath = "/usr/local/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        apiVersion = "1.4.359",
    )

    fun of(kind: GpuKind): GpuDriver = when (kind) {
        GpuKind.MALI -> mali
        GpuKind.TURNIP -> turnip
    }

    /** Pacman for Mesa and the loader, then the pinned blob unless that exact file is already there. */
    fun installScript(driver: GpuDriver): String = pacmanInstall(driver.packages) + " && " + fetch(driver)

    /**
     * Vulkan finds the GPU through the driver, and OpenGL runs on it through zink.
     * Offscreen, so no window is needed. The software-rendering variable is unset
     * because checks run while apps still render on the CPU, and that setting would win.
     */
    fun probeScript(driver: GpuDriver): String {
        // The guest still has SOFTWARE_ENV here. The loader prefers VK_DRIVER_FILES
        // over VK_ICD_FILENAMES, so both have to name this ICD or the check sees no driver.
        val exports = driver.env.joinToString(" ") { entry ->
            val key = entry.substringBefore("=")
            val value = entry.substringAfter("=")
            "export $key=${shellQuote(value)};"
        }
        val failure = shellQuote("Vulkan: the ${driver.label} driver did not load")
        return "{ unset LIBGL_ALWAYS_SOFTWARE VK_ICD_FILENAMES VK_DRIVER_FILES; " + exports +
            " vk=\$(timeout 60 vulkaninfo --summary 2>/dev/null); echo \"\$vk\" | grep -E 'deviceName|driverID';" +
            " { echo \"\$vk\" | grep -q ${shellQuote(driver.probeId)} || { echo $failure; false; }; } &&" +
            " { timeout 60 eglinfo -B -p surfaceless 2>/dev/null | grep 'renderer: zink' || { echo 'OpenGL: zink did not start on the GPU'; false; }; }; }"
    }

    /** Rewrites the ICD manifest for a library that is already installed. No download, no pacman. */
    fun manifestScript(driver: GpuDriver): String =
        "{ echo '${driver.librarySha256}  ${driver.driverPath}' | sha256sum -c - && " + writeManifest(driver) + "; }"

    private fun fetch(driver: GpuDriver): String {
        val download = when (val payload = driver.payload) {
            is DriverPayload.RawLibrary -> rawDownload(driver, payload)
            is DriverPayload.ArchPackage -> packageDownload(driver, payload)
        }
        return """
            { echo '${driver.librarySha256}  ${driver.driverPath}' | sha256sum -c - 2>/dev/null || { $download; }; } &&
            ${writeManifest(driver)}
            """.trimIndent().replace("\n", " ")
    }

    private fun writeManifest(driver: GpuDriver): String {
        val icdDir = driver.icdPath.substringBeforeLast('/')
        val icd = """{"file_format_version":"1.0.1","ICD":{"library_path":"${driver.driverPath}","api_version":"${driver.apiVersion}"}}"""
        val retireLegacy = if (driver.kind == GpuKind.MALI) {
            "if grep -F -q ${shellQuote(driver.driverPath)} ${shellQuote(LEGACY_MALI_ICD)} 2>/dev/null; then rm -f ${shellQuote(LEGACY_MALI_ICD)}; fi && "
        } else {
            ""
        }
        return retireLegacy + "mkdir -p ${shellQuote(icdDir)} && printf '%s\\n' ${shellQuote(icd)} > ${shellQuote(driver.icdPath)}"
    }

    /**
     * The trap runs on any exit, including a hash or tar failure. Cleanup used
     * to sit at the end of the && chain, so a failed download left the archive
     * in the guest.
     */
    private fun rawDownload(driver: GpuDriver, payload: DriverPayload.RawLibrary): String {
        val tmp = "/tmp/omalauncher-gpu.so"
        val body = """
            curl -fL --retry 3 -o $tmp ${driver.url} &&
            echo '${payload.sha256}  $tmp' | sha256sum -c - &&
            install -Dm755 $tmp ${driver.driverPath}
            """.trimIndent().replace("\n", " ")
        return guarded(body)
    }

    private fun packageDownload(driver: GpuDriver, payload: DriverPayload.ArchPackage): String {
        requireSafeMember(payload.member)
        val pkg = "/tmp/omalauncher-gpu.pkg.tar.xz"
        val dir = "/tmp/omalauncher-gpu-extract"
        val body = """
            curl -fL --retry 3 -o $pkg ${driver.url} &&
            echo '${payload.packageSha256}  $pkg' | sha256sum -c - &&
            rm -rf $dir && mkdir -p $dir &&
            tar -xJf $pkg -C $dir ${payload.member} &&
            echo '${payload.librarySha256}  $dir/${payload.member}' | sha256sum -c - &&
            install -Dm755 $dir/${payload.member} ${driver.driverPath}
            """.trimIndent().replace("\n", " ")
        return guarded(body)
    }

    private fun guarded(body: String): String =
        "{ trap 'rm -rf /tmp/omalauncher-gpu.so /tmp/omalauncher-gpu.pkg.tar.xz /tmp/omalauncher-gpu-extract' EXIT; $body; }"

    /** A package member is a relative path. It is a constant today; this refuses a later edit that is not. */
    private fun requireSafeMember(member: String) {
        val parts = member.split('/')
        require(
            member.isNotEmpty() &&
                !member.startsWith("/") &&
                ".." !in parts &&
                parts.none { it.isEmpty() } &&
                "'" !in member &&
                " " !in member,
        ) { "unsafe archive member" }
    }
}
