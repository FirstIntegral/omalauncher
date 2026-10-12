package dev.omalauncher.linux

/**
 * The pinned Vulkan drivers [Gpu] can install. Detection and the guest
 * scripts live here, with no Android types, so they can be tested on the JVM.
 *
 * Mali keeps the PanVK-kbase blob. Adreno gets Turnip built for KGSL, which
 * is what Android exposes at /dev/kgsl-3d0; Arch's own Mesa talks to DRM and
 * would not see that node.
 */
internal enum class GpuKind { MALI, TURNIP }

internal data class GpuDriver(
    val kind: GpuKind,
    /** What vulkaninfo must print before the driver is trusted. */
    val probeId: String,
    /** Short name for the settings row and the probe's failure line. */
    val label: String,
    val packages: List<String>,
    val url: String,
    /** sha256 of the file left at [driverPath]. */
    val blobSha256: String,
    val driverPath: String,
    val icdPath: String,
    val apiVersion: String,
    /** sha256 of [url] when it is an Arch package rather than the .so itself. */
    val packageSha256: String? = null,
    /** Path of the .so inside that package. */
    val archiveMember: String? = null,
) {
    /** Vulkan through this ICD, presented as wl_shm; OpenGL is zink on top of it. */
    val env: List<String> =
        listOf("VK_ICD_FILENAMES=$icdPath", "MESA_VK_WSI_DEBUG=sw", "GALLIUM_DRIVER=zink")
}

/**
 * Mali when /dev/mali0 exists, else Turnip when /dev/kgsl-3d0 exists.
 * A phone has one of these. Mali wins if a build exposes both, so a Mali
 * phone keeps the driver it has always had.
 */
internal fun gpuKind(
    hasMali: Boolean,
    hasKgsl: Boolean,
): GpuKind? = when {
    hasMali -> GpuKind.MALI
    hasKgsl -> GpuKind.TURNIP
    else -> null
}

internal object GpuDrivers {
    private val basePackages = listOf("mesa", "vulkan-icd-loader", "vulkan-tools", "mesa-utils")

    private val mali = GpuDriver(
        kind = GpuKind.MALI,
        probeId = "DRIVER_ID_MESA_PANVK",
        label = "Mali",
        packages = basePackages,
        // PanVK-kbase 0.1.0 rc2, the glibc build. Tested upstream on a Pixel 9a (Mali-G715).
        url = "https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/panvk-kbase-v0.1.0-rc2/libvulkan_panfrost-glibc-aarch64.so",
        blobSha256 = "0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820",
        driverPath = "/usr/local/lib64/libvulkan_panfrost.so",
        icdPath = "/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json",
        apiVersion = "1.4.363",
    )

    private val turnip = GpuDriver(
        kind = GpuKind.TURNIP,
        probeId = "DRIVER_ID_MESA_TURNIP",
        label = "Turnip",
        // The .so needs libSPIRV-Tools.so and libxcb-keysyms.so.1, which mesa does not always pull in.
        packages = basePackages + listOf("spirv-tools", "xcb-util-keysyms"),
        // lfdevs turnip-26.3.0-devel-20260824, the Arch aarch64 vulkan-freedreno package (KGSL, glibc).
        url = "https://github.com/lfdevs/mesa-for-android-container/releases/download/turnip-26.3.0-devel-20260824/vulkan-freedreno-1-26.3.0-1-aarch64.pkg.tar.xz",
        blobSha256 = "d7e22a8be00e94482a0bfc7b769f4c7c9b03573532f3257c42242d1502e9f69f",
        packageSha256 = "9824514d7180402d936f83f4ad422f338b7df31ff2804eab8a0badb13ee44a77",
        archiveMember = "usr/lib/libvulkan_freedreno.so",
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
    fun probeScript(driver: GpuDriver): String =
        "{ unset LIBGL_ALWAYS_SOFTWARE; " + driver.env.joinToString(" ") { "export $it;" } +
            " vk=\$(timeout 60 vulkaninfo --summary 2>/dev/null); echo \"\$vk\" | grep -E 'deviceName|driverID';" +
            " { echo \"\$vk\" | grep -q ${driver.probeId} || { echo 'Vulkan: the ${driver.label} driver did not load'; false; }; } &&" +
            " { timeout 60 eglinfo -B -p surfaceless 2>/dev/null | grep 'renderer: zink' || { echo 'OpenGL: zink did not start on the GPU'; false; }; }; }"

    private fun fetch(driver: GpuDriver): String {
        val member = driver.archiveMember
        val pkgSha = driver.packageSha256
        val download = if (member == null || pkgSha == null) {
            """
            curl -fL --retry 3 -o /tmp/panvk.so ${driver.url} &&
            echo '${driver.blobSha256}  /tmp/panvk.so' | sha256sum -c - &&
            install -Dm755 /tmp/panvk.so ${driver.driverPath} && rm -f /tmp/panvk.so
            """.trimIndent().replace("\n", " ")
        } else {
            """
            curl -fL --retry 3 -o /tmp/turnip.pkg.tar.xz ${driver.url} &&
            echo '$pkgSha  /tmp/turnip.pkg.tar.xz' | sha256sum -c - &&
            rm -rf /tmp/turnip-driver && mkdir -p /tmp/turnip-driver &&
            tar -xJf /tmp/turnip.pkg.tar.xz -C /tmp/turnip-driver $member &&
            echo '${driver.blobSha256}  /tmp/turnip-driver/$member' | sha256sum -c - &&
            install -Dm755 /tmp/turnip-driver/$member ${driver.driverPath} &&
            rm -rf /tmp/turnip.pkg.tar.xz /tmp/turnip-driver
            """.trimIndent().replace("\n", " ")
        }
        val icdDir = driver.icdPath.substringBeforeLast('/')
        val icd = """{"file_format_version":"1.0.1","ICD":{"library_path":"${driver.driverPath}","api_version":"${driver.apiVersion}"}}"""
        return """
            { echo '${driver.blobSha256}  ${driver.driverPath}' | sha256sum -c - 2>/dev/null || { $download; }; } &&
            mkdir -p $icdDir &&
            printf '%s\n' '$icd' > ${driver.icdPath}
            """.trimIndent().replace("\n", " ")
    }
}
