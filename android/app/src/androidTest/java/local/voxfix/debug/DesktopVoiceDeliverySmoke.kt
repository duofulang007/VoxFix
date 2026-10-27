package local.voxfix.debug

import android.app.Instrumentation
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import java.io.File
import java.util.concurrent.ExecutorService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Isolated input connections and temporary dictionaries; never edits a user's host or files. */
internal object DesktopVoiceDeliverySmoke {
    fun pauseNoise(instrumentation: Instrumentation): String {
        DesktopSpeechEngine(instrumentation.targetContext.assets).use { engine ->
            for (style in listOf("pink", "brown")) for (size in listOf(6400, 10400)) {
                val name = "pause-$style-$size-1.pcm"
                val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
                check(bytes.size == size * 2)
                val pcm = ShortArray(size)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                val audio = FloatArray(size) { pcm[it] / 32768f }
                var offset = 0
                val text = engine.transcribe({
                    if (offset == size) null else {
                        val end = minOf(offset + 320, size)
                        audio.copyOfRange(offset, end).also { offset = end }
                    }
                }, {})
                check(text.isEmpty()) { "Non-speech produced words: $name -> $text" }
            }
        }
        return "DESKTOP_PAUSE_NOISE_PASS cases=4 pink/brown no-inserted-yeah"
    }

    fun shortSpeech(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val folder = File(context.getExternalFilesDir(null), "voice-audit-20261026")
        val rows = JSONObject(File(folder, "plan.json").readText()).getJSONArray("rows")
        val output = JSONArray()
        DesktopSpeechEngine(context.assets).use { engine ->
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                check(id.matches(Regex("[a-z0-9-]+")))
                val bytes = File(folder, "$id.pcm").readBytes()
                check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == row.getString("sha256"))
                val pcm = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
                var offset = 0
                val text = engine.transcribe({
                    if (offset == audio.size) null else {
                        val end = minOf(offset + 320, audio.size)
                        audio.copyOfRange(offset, end).also { offset = end }
                    }
                }, {})
                val passed = when (row.getString("kind")) {
                    "recovered" -> text.replace(Regex("[^\\p{L}\\p{N}]"), "").lowercase(java.util.Locale.ROOT) ==
                        row.getString("expected").replace(Regex("[^\\p{L}\\p{N}]"), "").lowercase(java.util.Locale.ROOT)
                    "silence", "unresolved" -> text.isEmpty()
                    else -> text == DesktopVoiceRules.clean(row.getString("baseline"))
                }
                output.put(JSONObject().put("id", id).put("kind", row.getString("kind"))
                    .put("expected", row.optString("expected")).put("text", text).put("passed", passed))
            }
        }
        File(folder, "result.json").writeText(JSONObject().put("rows", output).toString(2))
        val failures = (0 until output.length()).count { !output.getJSONObject(it).getBoolean("passed") }
        check(failures == 0) { "Short speech regressions: $failures; inspect voice-audit-20261026/result.json" }
        val recovered = (0 until output.length()).count { output.getJSONObject(it).getString("kind") == "recovered" }
        val unresolved = (0 until output.length()).count { output.getJSONObject(it).getString("kind") == "unresolved" }
        return "DESKTOP_SHORT_PASS cases=${output.length()} recovered=$recovered unresolved=$unresolved silence/baseline"
    }

    private class Host(view: View) : BaseInputConnection(view, true) {
        var finishes = 0
        override fun finishComposingText(): Boolean { finishes++; return super.finishComposingText() }
    }

    private class Ime(private val host: InputConnection) : VoxFixImeService() {
        override fun getCurrentInputConnection() = host
    }

    private fun field(name: String) = VoxFixImeService::class.java.getDeclaredField(name).apply { isAccessible = true }

    fun run(instrumentation: Instrumentation): String {
        var checks = 0
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            val host = Host(View(instrumentation.targetContext))
            val ime = Ime(host)
            try {
                val session = field("session").get(ime) as InputSession
                fun open(local: Boolean): Long {
                    val ticket = session.open()
                    field("ticket").set(ime, ticket)
                    field("shown").set(ime, true)
                    field("localVoice").set(ime, local)
                    field("inputGeneration").set(ime, 1L)
                    field("voiceGeneration").set(ime, 1L)
                    return ticket
                }
                fun cancel() = VoxFixImeService::class.java.getDeclaredMethod("cancelRecording").apply {
                    isAccessible = true
                }.invoke(ime)

                host.commitText("原文", 1)
                host.setSelection(0, 2)
                var ticket = open(true)
                check(!ime.writeVoiceDraft("内部草稿", 0, ticket, 1L))
                check(!ime.writeVoiceSettled("内部定稿", 0, ticket, 1L))
                cancel()
                check(host.editable.toString() == "原文" && host.finishes == 0)
                checks++

                ticket = open(false)
                check(ime.writeVoiceDraft("可见草稿", 0, ticket, 1L))
                cancel()
                check(host.editable.toString() == "可见草稿" && host.finishes == 1)
                check(BaseInputConnection.getComposingSpanStart(checkNotNull(host.editable)) == -1)
                check(!ime.writeVoiceSettled("迟到定稿", 0, ticket, 1L))
                checks++

                ticket = open(false)
                check(ime.writeVoiceSettled("已提交。", 0, ticket, 1L))
                val committed = host.editable.toString()
                cancel()
                check(host.editable.toString() == committed && host.finishes == 1)
                checks++

                // A paused draft and its delayed final must never insert the same speech twice.
                checkNotNull(host.editable).clear()
                ticket = open(false)
                val planner = DesktopVoiceSegments(FloatArray(16000))
                repeat(10) { planner.append(FloatArray(1600)) }
                val draft = checkNotNull(planner.frame(7808, emptyList()).draft)
                val final = planner.frame(-1, listOf(DesktopVoiceBounds(5000, 15000)), true).finals.single()
                check(draft.start != final.start && draft.start == final.key)
                check(ime.writeVoiceDraft("上一句话", draft.start, ticket, 1L))
                VoxFixImeService::class.java.getDeclaredMethod("pauseVoiceForTyping").apply { isAccessible = true }.invoke(ime)
                (field("voiceMuted").get(ime) as java.util.concurrent.atomic.AtomicBoolean).set(false)
                check(!ime.writeVoiceDraft("迟到草稿", draft.start, ticket, 1L))
                check(ime.writeVoiceDraft("第二句话", 16000, ticket, 1L))
                VoxFixImeService::class.java.getDeclaredMethod("pauseVoiceForTyping").apply { isAccessible = true }.invoke(ime)
                (field("voiceMuted").get(ime) as java.util.concurrent.atomic.AtomicBoolean).set(false)
                check(!ime.writeVoiceSettled("上一句话。", final.key, ticket, 1L))
                check(!ime.writeVoiceSettled("第二句话。", 16000, ticket, 1L))
                check(ime.writeVoiceSettled("上一句话。", 32000, ticket, 1L))
                check(host.editable.toString() == "上一句话第二句话上一句话。") { "Unexpected repeated or missing speech: ${host.editable}" }
                cancel()
                host.finishes = 1
                checks++

                ticket = open(false)
                check(!ime.writeVoiceDraft("旧框", 0, ticket, 0L))
                check(!ime.writeVoiceSettled("旧框", 0, ticket, 0L))
                field("composingStart").set(ime, 0)
                check(!ime.writeVoiceDraft("覆盖拼音", 0, ticket, 1L))
                host.setComposingText("pin", 1)
                cancel()
                check(host.editable.toString().endsWith("pin") && host.finishes == 1)
                checks++

                open(false)
                field("voiceTyping").set(ime, true)
                val muted = field("voiceMuted").get(ime) as java.util.concurrent.atomic.AtomicBoolean
                muted.set(true)
                val resume = field("resumeVoiceInput").get(ime) as Runnable
                field("candidatesShown").set(ime, true)
                resume.run(); check(muted.get())
                field("candidatesShown").set(ime, false)
                field("composingStart").set(ime, 0)
                resume.run(); check(muted.get())
                field("composingStart").set(ime, null)
                resume.run(); check(!muted.get() && !(field("voiceTyping").get(ime) as Boolean))
                cancel()
                checks++

                // Exercise the real countdown without opening an Activity or reading its dictionary.
                val page = DictionaryActivity()
                fun editorField(name: String) = DictionaryActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                val content = android.widget.LinearLayout(instrumentation.targetContext)
                val search = View(instrumentation.targetContext)
                val bar = android.widget.LinearLayout(instrumentation.targetContext).apply { tag = "dictionary-undo" }
                val countdown = android.widget.TextView(instrumentation.targetContext).apply { tag = "dictionary-undo-countdown" }
                bar.addView(countdown); content.addView(bar); content.addView(search)
                editorField("content").set(page, content)
                editorField("deleted").set(page, VoiceDictionary.Entry("测试", mutableListOf()) to 0)
                editorField("deletedUntil").set(page, android.os.SystemClock.uptimeMillis() + 10_000L)
                val tick = editorField("undoTick").get(page) as Runnable
                try {
                    tick.run()
                    check(countdown.text.toString().startsWith("撤回 · "))
                    check(content.getChildAt(0) === bar && search.parent === content)
                    check(editorField("deleted").get(page) != null)
                    editorField("deletedUntil").set(page, android.os.SystemClock.uptimeMillis() - 1L)
                    tick.run()
                    check(editorField("deleted").get(page) == null && bar.parent == null && search.parent === content)
                    checks++
                } finally { (editorField("handler").get(page) as android.os.Handler).removeCallbacksAndMessages(null) }
            } catch (error: Throwable) { failure = error }
            finally {
                for (name in listOf("worker", "voiceWarmup", "pinyinLoader"))
                    (field(name).get(ime) as ExecutorService).shutdownNow()
            }
        }
        failure?.let { throw it }

        val folder = File(instrumentation.targetContext.cacheDir, "audit-dictionary-${System.nanoTime()}")
        check(folder.mkdir())
        try {
            for (broken in listOf("broken JSON", "{\"hotwords\":[\"API\"]} trailing", "[]",
                "{\"hotwords\":42}", "{\"rules\":[{\"to\":\"API\",\"from\":false}]}")) {
                check(runCatching { VoiceDictionary.parse(broken) }.isFailure)
            }
            val dictionary = VoiceDictionary(listOf(VoiceDictionary.Entry("API", mutableListOf("a p i"))))
            val file = File(folder, VoiceDictionaryStore.NAME)
            VoiceDictionaryStore.write(file, dictionary)
            val saved = file.readText(Charsets.UTF_8)
            check(VoiceDictionary.parse(saved).serialize() == saved)
            check(File(folder, file.name + ".new").mkdir())
            check(runCatching { VoiceDictionaryStore.write(file, VoiceDictionary()) }.isFailure)
            check(file.readText(Charsets.UTF_8) == saved)
            checks++

            file.writeText("broken JSON")
            VoiceDictionaryStore.invalidate()
            check(VoiceDictionaryStore.load(file).entries.isEmpty())
            check(file.readText() == "broken JSON")
            checks++
        } finally {
            VoiceDictionaryStore.invalidate()
            check(folder.deleteRecursively())
        }
        return "DESKTOP_DELIVERY_PASS checks=$checks local-isolation cancel-freeze late-results dictionary-undo corruption atomic-save"
    }
}
