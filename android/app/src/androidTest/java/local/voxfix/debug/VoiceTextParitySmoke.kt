package local.voxfix.debug

import android.app.Instrumentation
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real Android composing and selection semantics, isolated from the user's active editor. */
internal object VoiceTextParitySmoke {
    private class Host(view: View) : BaseInputConnection(view, true) {
        var readOnly = false
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            if (readOnly) true else super.setComposingText(text, newCursorPosition)
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            if (readOnly) true else super.commitText(text, newCursorPosition)
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().apply {
            text = editable.toString(); startOffset = 0
            selectionStart = Selection.getSelectionStart(editable)
            selectionEnd = Selection.getSelectionEnd(editable)
        }
    }

    fun run(instrumentation: Instrumentation): String {
        var checks = 0
        instrumentation.runOnMainSync {
            fun host(text: String, start: Int, end: Int = start) = Host(View(instrumentation.targetContext)).apply {
                commitText(text, 1); setSelection(start, end)
            }
            val host = host("前文旧词后文", 2, 4)
            val target = VoiceTextTarget(host)
            val frames = mutableListOf<String>()
            val reveal = VoiceTextReveal { text, key, final ->
                target.write(text, key, final).also { if (it) frames.add(host.editable.toString()) }
            }
            reveal.offer("新词正确内容", 0, false, 0)
            reveal.tick(0); check(host.editable.toString() == "前文新后文")
            reveal.tick(35)
            reveal.offer("新辞正确内容", 0, false, 40); reveal.tick(80)
            reveal.offer("新词正确内容。", 0, true, 100); reveal.tick(400)
            check(host.editable.toString() == "前文新词正确内容。后文")
            check(Selection.getSelectionStart(host.editable) == 9)
            check(BaseInputConnection.getComposingSpanStart(checkNotNull(host.editable)) == -1)
            check(frames.size >= 3 && frames.all { it.startsWith("前文") && it.endsWith("后文") })
            checks++

            check(target.write("后一句。", 16000, true))
            val fixed = host.editable.toString()
            check(fixed == "前文新词正确内容。后一句。后文"); checks++
            host.setSelection(0, 2)
            check(!target.write("不能覆盖新选区", 32000, true))
            check(target.blocked && host.editable.toString() == fixed); checks++

            val edited = host("保留正文", 4)
            val own = VoiceTextTarget(edited)
            check(own.write("识别草稿", 0, false))
            edited.finishComposingText(); edited.commitText("手动改动", 1)
            val manual = edited.editable.toString()
            check(!own.write("迟到定稿。", 0, true))
            check(edited.editable.toString() == manual); checks++

            val readOnly = host("前文", 2)
            val guard = VoiceTextTarget(readOnly)
            check(guard.write("草稿", 0, false)); readOnly.readOnly = true
            check(!guard.write("草稿还未显示的尾巴。", 0, true))
            check(guard.blocked && guard.shown == "草稿" && readOnly.editable.toString() == "前文草稿"); checks++

            val cancelled = host("前文保留后文", 2, 4)
            val cancel = VoiceTextTarget(cancelled)
            check(cancel.write("已见", 0, false)); cancel.freeze()
            check(cancelled.editable.toString() == "前文已见后文")
            check(BaseInputConnection.getComposingSpanStart(checkNotNull(cancelled.editable)) == -1); checks++

            val numeric = host("两侧", 1)
            val number = VoiceTextTarget(numeric)
            check(number.write("不要付1285元", 0, false))
            check(number.write("不要付128.5元。", 0, true))
            check(numeric.editable.toString() == "两不要付128.5元。侧"); checks++

            val protected = host("原文", 2)
            val ime = object : VoxFixImeService() { override fun getCurrentInputConnection() = protected }
            fun field(name: String) = VoxFixImeService::class.java.getDeclaredField(name).apply { isAccessible = true }
            try {
                val session = field("session").get(ime) as InputSession
                field("ticket").set(ime, session.open())
                field("shown").set(ime, true)
                field("voiceGeneration").set(ime, 0L)
                val queue = field("voiceReveal").get(ime) as VoiceTextReveal
                queue.offer("待写定稿。", 0, true, 0); queue.tick(0)
                protected.readOnly = true
                (field("revealVoice").get(ime) as Runnable).run()
                check(field("voicePendingCommit").get(ime) == "待写定稿。")
                check(!queue.active && protected.editable.toString() == "原文待")
                checks++
            } finally {
                VoxFixImeService::class.java.getDeclaredMethod("cancelRecording").apply { isAccessible = true }.invoke(ime)
                for (name in listOf("worker", "voiceWarmup", "pinyinLoader"))
                    (field(name).get(ime) as java.util.concurrent.ExecutorService).shutdownNow()
            }
        }
        return "VOICE_TEXT_PARITY_PASS checks=$checks selection/correction/pacing/read-only/manual-edit/cancel/numbers"
    }

    fun continuous(instrumentation: Instrumentation): String {
        val bytes = instrumentation.context.assets.open("mix-huihui-5.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        var data = 12
        while (String(bytes, data, 4, Charsets.US_ASCII) != "data") data += 8 + buffer.getInt(data + 4).let { it + it % 2 }
        val count = buffer.getInt(data + 4) / 2
        check(buffer.getShort(22).toInt() == 1 && buffer.getShort(34).toInt() == 16)
        val rate = buffer.getInt(24)
        val original = FloatArray(count) { buffer.getShort(data + 8 + it * 2) / 32768f }
        val speech = FloatArray(count * 16000 / rate) { index ->
            val position = index.toDouble() * rate / 16000
            val left = position.toInt(); val fraction = (position - left).toFloat()
            original[left] * (1 - fraction) + original[minOf(left + 1, original.lastIndex)] * fraction
        }
        val finals = mutableListOf<Pair<Int, String>>()
        DesktopSpeechEngine(instrumentation.targetContext.assets).use { engine ->
            val total = DesktopVoiceRules.RATE * 370
            val starts = listOf(0, DesktopVoiceRules.RATE * 181, DesktopVoiceRules.RATE * 361)
            var position = 0
            val text = engine.transcribe({
                if (position == total) null else {
                    val size = minOf(1600, total - position)
                    FloatArray(size) { index ->
                        val at = position + index
                        val start = starts.firstOrNull { at >= it && at < it + speech.size }
                        if (start == null) 0f else speech[at - start]
                    }.also { position += size }
                }
            }, {}, onSettled = { value, key -> if (value.isNotBlank()) finals.add(key to value) })
            check(position == total && text.isNotBlank())
            check(finals.zipWithNext().all { (a, b) -> a.first < b.first })
            for (start in starts) check(finals.any { it.first in maxOf(0, start - 6400)..start + speech.size }) {
                "Missing speech after round at $start; final keys=${finals.map { it.first }}" }
            check(text == finals.fold("") { joined, value -> DesktopVoiceRules.join(joined, value.second) })
        }
        return "VOICE_CONTINUOUS_PASS audioSeconds=370 rounds=3 finals=${finals.size} no-microphone/no-history"
    }
}
