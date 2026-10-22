package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class AgentsTest {
    /** A missing alias would make opening that agent's window throw. */
    @Test fun everyAgentHasAWindowAlias() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aliases = Regex("""<activity-alias(.*?)/>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        for (agent in AGENTS) {
            val alias = aliases.firstOrNull { "android:name=\".${agent.windowAlias}\"" in it }
            assertTrue("${agent.bin}: no .${agent.windowAlias} in the manifest", alias != null)
            assertTrue("${agent.bin}: not labelled \"${agent.name}\"", "android:label=\"${agent.name}\"" in alias!!)
            assertTrue("${agent.bin}: no logo", "android:icon=\"@drawable/agent_${agent.bin.replace('-', '_')}\"" in alias)
        }
    }

    @Test fun copilotReceivesAnInteractivePromptAsOneLiteralArgument() {
        val prompt = "Explain \"it's quoted\"; \$(exit 17)\nand keep \$HOME literal."
        val script = """
            set -- ${agentInvocation("copilot", prompt)}
            test "${'$'}#" -eq 3 &&
            test "${'$'}1" = copilot &&
            test "${'$'}2" = -i &&
            test "${'$'}3" = "${'$'}EXPECTED_PROMPT"
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["EXPECTED_PROMPT"] = prompt }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    /** A prompt from a widget can't pass the agent an option: claude --settings=... would run its hooks. */
    @Test fun aPromptStartingWithADashIsNoOption() {
        val script = """
            set -- ${agentInvocation("claude", "--settings={}")}
            test "${'$'}#" -eq 2 &&
            test "${'$'}2" = " --settings={}"
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    /** Prune deletes what `mise ls --prunable` lists, only under mise's installs, then lets mise tidy up. */
    @Test fun pruneDeletesPrunableVersionsItself() {
        val dir = createTempDirectory("prune").toFile()
        val old = File(dir, "mise/installs/opencode/2.1.0").apply { mkdirs() }
        val kept = File(dir, "mise/installs/opencode/2.1.2").apply { mkdirs() }
        val outside = File(dir, "elsewhere").apply { mkdirs() }
        val stub = File(dir, "bin/mise").apply { parentFile.mkdirs() }
        stub.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = ls ]; then
              printf '{\n  "opencode": [\n    {\n      "version": "2.1.0",\n      "install_path": "%s",\n      "installed": true\n    },\n' "${old.path}"
              printf '    {\n      "install_path": "%s"\n    }\n  ]\n}\n' "${outside.path}"
            else
              echo "${'$'}*" >"${dir.path}/pruned"
            fi
            """.trimIndent(),
        )
        stub.setExecutable(true)
        val process = ProcessBuilder("sh", "-c", MISE_PRUNE).redirectErrorStream(true)
        process.environment()["PATH"] = "${stub.parent}:${System.getenv("PATH")}"
        val p = process.start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, p.waitFor())
        assertTrue("old version left", !old.exists())
        assertTrue("current version deleted", kept.exists())
        assertTrue("deleted outside mise's installs", outside.exists())
        assertEquals("prune -y\n", File(dir, "pruned").readText())
        dir.deleteRecursively()
    }

    /**
     * The first removal can fail and leave proot's `.l2s.*` helper behind. A later pass deletes it.
     * `mise prune -y` still runs after that.
     */
    @Test fun pruneRetriesADirectoryTheFirstRemovalLeft() {
        val dir = createTempDirectory("prune-retry").toFile()
        val old = File(dir, "mise/installs/opencode/2.1.1").apply { mkdirs() }
        File(old, ".l2s.opencode0001.0001").writeText("link")
        writeMiseStub(dir, old)
        File(dir, "bin/rm").apply {
            writeText(
                """
                #!/bin/sh
                target=
                for a in "${'$'}@"; do case "${'$'}a" in -*) ;; *) target=${'$'}a ;; esac; done
                echo "${'$'}target" >>"${dir.path}/rm-calls"
                if [ -e "${'$'}target/.l2s.opencode0001.0001" ]; then
                  /bin/rm -f "${'$'}target/.l2s.opencode0001.0001"
                  exit 1
                fi
                exec /bin/rm "${'$'}@"
                """.trimIndent(),
            )
            setExecutable(true)
        }
        val (output, code) = runPrune(dir)
        assertEquals(output, 0, code)
        assertTrue("old version left", !old.exists())
        assertEquals("first pass should fail and the second should delete", 2, File(dir, "rm-calls").readLines().size)
        assertEquals("prune -y\n", File(dir, "pruned").readText())
        dir.deleteRecursively()
    }

    /** A directory that never goes away is tried three times, then left for `mise prune -y`. */
    @Test fun pruneStopsAfterThreeFailedRemovals() {
        val dir = createTempDirectory("prune-stop").toFile()
        val old = File(dir, "mise/installs/opencode/2.1.1").apply { mkdirs() }
        writeMiseStub(dir, old)
        File(dir, "bin/rm").apply {
            writeText(
                """
                #!/bin/sh
                target=
                for a in "${'$'}@"; do case "${'$'}a" in -*) ;; *) target=${'$'}a ;; esac; done
                echo "${'$'}target" >>"${dir.path}/rm-calls"
                exit 1
                """.trimIndent(),
            )
            setExecutable(true)
        }
        val (output, code) = runPrune(dir)
        assertEquals(output, 0, code)
        assertTrue("gave up on a directory that is still there", old.exists())
        assertEquals(3, File(dir, "rm-calls").readLines().size)
        assertEquals("prune -y\n", File(dir, "pruned").readText())
        dir.deleteRecursively()
    }

    /** OpenCode installs from before 2 have no node, and mise needs it for OpenCode 2's postinstall. */
    @Test fun updatesAddNodeForOpencode() {
        assertEquals(
            "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g 'opencode' 'npm:@xai-official/grok' 'npm:@moonshot-ai/kimi-code')\" ]; " +
                "then mise use -g node@lts; fi",
            MISE_NODE_FOR_AGENTS,
        )
    }

    private fun writeMiseStub(dir: File, install: File) {
        File(dir, "bin/mise").apply {
            parentFile.mkdirs()
            writeText(
                """
                #!/bin/sh
                if [ "${'$'}1" = ls ]; then
                  printf '{ "opencode": [{ "install_path": "%s" }] }\n' "${install.path}"
                else
                  echo "${'$'}*" >"${dir.path}/pruned"
                fi
                """.trimIndent(),
            )
            setExecutable(true)
        }
    }

    private fun runPrune(dir: File): Pair<String, Int> {
        val process = ProcessBuilder("sh", "-c", MISE_PRUNE).redirectErrorStream(true)
        process.environment()["PATH"] = "${File(dir, "bin").path}:${System.getenv("PATH")}"
        val p = process.start()
        val output = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().use { output.append(it.readText()) } }
        reader.start()
        val finished = p.waitFor(10, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(1_000)
        assertTrue(output.toString() + "timed out", finished)
        return output.toString() to p.exitValue()
    }
}
