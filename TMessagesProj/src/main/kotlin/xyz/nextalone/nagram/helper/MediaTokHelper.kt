package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import xyz.nextalone.nagram.NaConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object MediaTokHelper : NotificationCenter.NotificationCenterDelegate {

    private const val PREFS_NAME = "mediatok_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"
    private const val KEY_WIPE_PEERS = "wipe_peers"
    private const val PREFIX_AUTODEL = "autodel_"
    private const val PREFIX_NOTE = "note_"
    private const val MAX_INLINE_MEDIA_BYTES = 16L * 1024L * 1024L

    private val registeredAccounts = HashSet<Int>()
    private val autoDeletePeers = ConcurrentHashMap<Long, Int>()
    private val wipePeers = CopyOnWriteArraySet<Long>()
    @Volatile
    private var prefsLoaded = false

    private fun prefs(): SharedPreferences {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun ensurePrefsLoaded() {
        if (prefsLoaded) return
        val sp = prefs()
        for ((k, v) in sp.all) {
            if (k.startsWith(PREFIX_AUTODEL) && v is Int && v > 0) {
                k.removePrefix(PREFIX_AUTODEL).toLongOrNull()?.let { dialogId ->
                    autoDeletePeers[dialogId] = v
                }
            }
        }
        sp.getStringSet(KEY_WIPE_PEERS, emptySet())?.forEach { raw ->
            raw.toLongOrNull()?.let { wipePeers.add(it) }
        }
        prefsLoaded = true
    }

    @JvmStatic
    fun ensureObservers(account: Int) {
        ensurePrefsLoaded()
        synchronized(registeredAccounts) {
            if (registeredAccounts.contains(account)) return
            registeredAccounts.add(account)
            AndroidUtilities.runOnUIThread {
                val nc = NotificationCenter.getInstance(account)
                nc.addObserver(this, NotificationCenter.messageReceivedByServer)
                nc.addObserver(this, NotificationCenter.didReceiveNewMessages)
            }
        }
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.messageReceivedByServer) {
            if (args.size < 4) return
            val newMsgId = (args[1] as? Int) ?: return
            if (newMsgId <= 0) return
            val msgObj = args[2] as? TLRPC.Message
            val dialogId = (args[3] as? Long) ?: msgObj?.dialog_id ?: return
            val scheduled = if (args.size >= 7) (args[6] as? Boolean) ?: false else false
            if (scheduled) return
            val seconds = autoDeletePeers[dialogId] ?: return
            if (seconds <= 0) return
            AndroidUtilities.runOnUIThread({
                revokeMessages(account, dialogId, listOf(newMsgId))
            }, seconds * 1000L)
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (args.size < 2) return
            val dialogId = (args[0] as? Long) ?: return
            if (dialogId <= 0L || !DialogObject.isUserDialog(dialogId)) return
            if (!wipePeers.contains(dialogId)) return
            @Suppress("UNCHECKED_CAST")
            val messages = args[1] as? ArrayList<MessageObject> ?: return
            val toRevoke = ArrayList<Int>()
            for (m in messages) {
                if (!m.isOut && m.id > 0 && !m.scheduled) {
                    toRevoke.add(m.id)
                }
            }
            if (toRevoke.isNotEmpty()) {
                AndroidUtilities.runOnUIThread({
                    revokeMessages(account, dialogId, toRevoke)
                }, 600L)
            }
        }
    }

    private fun revokeMessages(account: Int, dialogId: Long, ids: List<Int>) {
        if (ids.isEmpty()) return
        try {
            val list = ArrayList<Int>(ids)
            MessagesController.getInstance(account).deleteMessages(list, null, null, dialogId, 0, true, 0)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun handleOutgoingCommand(account: Int, params: SendMessagesHelper.SendMessageParams?): Boolean {
        if (params == null) return false
        ensureObservers(account)

        if (params.retryMessageObject != null ||
            params.document != null ||
            params.photo != null ||
            params.videoEditedInfo != null ||
            params.poll != null ||
            params.richMessage != null ||
            params.scheduleDate != 0
        ) {
            return false
        }

        val raw = params.message?.trim() ?: return false
        if (!raw.startsWith(".")) return false

        val parts = raw.split(Regex("\\s+"), limit = 2)
        val cmdLower = parts[0].lowercase(Locale.ROOT)
        val arg = if (parts.size > 1) parts[1].trim() else ""
        val dialogId = params.peer

        return when (cmdLower) {
            ".mt", ".help" -> {
                showHelpDialog(account, dialogId)
                true
            }
            ".mtkey", ".key" -> {
                if (arg.isBlank()) {
                    openSettingsDialog(getActivity())
                } else {
                    prefs().edit().putString(KEY_GEMINI_API, arg).apply()
                    toast("MediaTok: Gemini API ключ сохранён")
                }
                true
            }
            ".autodel" -> {
                handleAutoDeleteCommand(dialogId, arg)
                true
            }
            ".delme" -> {
                handleDelMeCommand(account, dialogId, arg)
                true
            }
            ".wipe" -> {
                if (dialogId > 0L && DialogObject.isUserDialog(dialogId)) {
                    wipePeers.add(dialogId)
                    saveWipePeers()
                    toast("MediaTok: авто-удаление входящих ВКЛ для этого чата")
                } else {
                    toast("MediaTok: .wipe работает только в личных чатах")
                }
                true
            }
            ".unwipe" -> {
                wipePeers.remove(dialogId)
                saveWipePeers()
                toast("MediaTok: авто-удаление входящих ВЫКЛ")
                true
            }
            ".ai", ".ai!" -> {
                val sendToChat = cmdLower.endsWith("!")
                runAiPromptCommand(account, dialogId, params.replyToMsg, params.replyToTopMsg, arg, sendToChat)
                true
            }
            ".tldr", ".tldr!" -> {
                val sendToChat = cmdLower.endsWith("!")
                val count = arg.toIntOrNull()?.coerceIn(5, 120) ?: 40
                runTldrCommand(account, dialogId, params.replyToTopMsg, count, sendToChat)
                true
            }
            ".tr", ".tr!" -> {
                val sendToChat = cmdLower.endsWith("!")
                runTranslateCommand(account, dialogId, params.replyToMsg, params.replyToTopMsg, arg, sendToChat)
                true
            }
            ".fix", ".fix!" -> {
                val sendToChat = cmdLower.endsWith("!")
                val targetText = resolveCommandText(arg, params.replyToMsg)
                if (targetText.isBlank()) {
                    toast("MediaTok: укажите текст после .fix или ответьте на сообщение")
                } else {
                    runTextTransformCommand(
                        account, dialogId, params.replyToMsg, params.replyToTopMsg,
                        "Исправь орфографию, пунктуацию и стиль текста на том же языке. Верни только готовый исправленный текст без пояснений:",
                        targetText, sendToChat, "Исправленный текст"
                    )
                }
                true
            }
            ".tone", ".tone!" -> {
                val sendToChat = cmdLower.endsWith("!")
                val splitTone = arg.split(Regex("\\s+"), limit = 2)
                val style = splitTone.getOrNull(0)?.trim().orEmpty()
                val textBody = if (splitTone.size > 1) splitTone[1].trim() else extractText(params.replyToMsg)
                if (style.isBlank() || textBody.isBlank()) {
                    toast("MediaTok: формат .tone <стиль> <текст> (или реплай)")
                } else {
                    runTextTransformCommand(
                        account, dialogId, params.replyToMsg, params.replyToTopMsg,
                        "Перепиши следующий текст в тоне/стиле «$style», сохраняя смысл и язык. Верни только итоговый текст без кавычек и комментариев:",
                        textBody, sendToChat, "Тон: $style"
                    )
                }
                true
            }
            else -> false
        }
    }

    private fun handleAutoDeleteCommand(dialogId: Long, arg: String) {
        val clean = arg.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty() || clean == "status" || clean == "статус") {
            val sec = autoDeletePeers[dialogId] ?: 0
            toast(if (sec > 0) "MediaTok авто-удаление: ${sec}s" else "MediaTok авто-удаление: выключено")
            return
        }
        if (clean == "off" || clean == "0" || clean == "выкл") {
            autoDeletePeers.remove(dialogId)
            prefs().edit().remove(PREFIX_AUTODEL + dialogId).apply()
            toast("MediaTok: авто-удаление в чате ВЫКЛ")
            return
        }
        val seconds = parseDurationSeconds(clean)
        if (seconds == null || seconds <= 0) {
            toast("MediaTok: укажите время (10s, 1m, 1h, 1d или off)")
            return
        }
        autoDeletePeers[dialogId] = seconds
        prefs().edit().putInt(PREFIX_AUTODEL + dialogId, seconds).apply()
        toast("MediaTok: исходящие будут удаляться через $clean")
    }

    private fun handleDelMeCommand(account: Int, dialogId: Long, arg: String) {
        val clean = arg.trim().lowercase(Locale.ROOT)
        var maxCount = 100
        var minTimestamp = 0
        if (clean.isNotEmpty()) {
            val asNum = clean.toIntOrNull()
            if (asNum != null) {
                maxCount = asNum.coerceIn(1, 500)
            } else {
                val secs = parseDurationSeconds(clean)
                if (secs != null && secs > 0) {
                    maxCount = 500
                    minTimestamp = ConnectionsManager.getInstance(account).currentTime - secs
                } else {
                    toast("MediaTok: формат .delme [кол-во | 10m | 2h]")
                    return
                }
            }
        }

        val selfUser = UserConfig.getInstance(account).currentUser
        val peer = MessagesController.getInstance(account).getInputPeer(dialogId)
        val selfPeer = if (selfUser != null) MessagesController.getInputPeer(selfUser) else TLRPC.TL_inputPeerSelf()
        if (peer == null) {
            toast("MediaTok: чат не найден")
            return
        }

        toast("MediaTok: удаляю ваши сообщения...")
        val collected = ArrayList<Int>()
        fun fetchNext(offsetId: Int) {
            val req = TLRPC.TL_messages_search()
            req.peer = peer
            req.q = ""
            req.filter = TLRPC.TL_inputMessagesFilterEmpty()
            req.from_id = selfPeer
            req.flags = req.flags or 1
            req.offset_id = offsetId
            req.limit = 100
            ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
                if (error != null || response !is TLRPC.messages_Messages) {
                    AndroidUtilities.runOnUIThread {
                        if (collected.isNotEmpty()) {
                            revokeInBatches(account, dialogId, collected)
                        } else {
                            toast("MediaTok: ошибка поиска (${error?.text ?: "unknown"})")
                        }
                    }
                    return@sendRequest
                }
                val msgs = response.messages
                if (msgs.isEmpty()) {
                    AndroidUtilities.runOnUIThread { revokeInBatches(account, dialogId, collected) }
                    return@sendRequest
                }
                var reachedCutoff = false
                var lastId = offsetId
                for (m in msgs) {
                    lastId = m.id
                    if (minTimestamp > 0 && m.date < minTimestamp) {
                        reachedCutoff = true
                        break
                    }
                    if (m.id > 0) {
                        collected.add(m.id)
                    }
                    if (collected.size >= maxCount) {
                        reachedCutoff = true
                        break
                    }
                }
                if (!reachedCutoff && msgs.size >= 100 && lastId > 0) {
                    fetchNext(lastId)
                } else {
                    AndroidUtilities.runOnUIThread { revokeInBatches(account, dialogId, collected) }
                }
            }
        }
        fetchNext(0)
    }

    private fun revokeInBatches(account: Int, dialogId: Long, ids: List<Int>) {
        if (ids.isEmpty()) {
            toast("MediaTok: подходящие сообщения не найдены")
            return
        }
        var idx = 0
        while (idx < ids.size) {
            val end = minOf(idx + 100, ids.size)
            revokeMessages(account, dialogId, ids.subList(idx, end))
            idx = end
        }
        toast("MediaTok: удалено сообщений — ${ids.size}")
    }

    private fun runAiPromptCommand(
        account: Int,
        dialogId: Long,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        userPrompt: String,
        sendToChat: Boolean
    ) {
        val contextChain = buildReplyChain(account, replyToMsg)
        val mediaFile = findReplyMedia(account, replyToMsg)
        if (userPrompt.isBlank() && contextChain.isBlank() && mediaFile == null) {
            toast("MediaTok: введите запрос после .ai или ответьте на сообщение/медиа")
            return
        }

        val finalPrompt = buildString {
            if (contextChain.isNotBlank()) {
                append("Контекст переписки (от старых к новым):\n")
                append(contextChain)
                append("\n\n")
            }
            if (userPrompt.isNotBlank()) {
                append("Запрос пользователя: ")
                append(userPrompt)
            } else if (mediaFile != null) {
                append("Подробно опиши или расшифруй приложенное медиа на русском языке.")
            } else {
                append("Кратко и по делу прокомментируй / ответь на сообщение выше на русском языке.")
            }
        }

        executeAi(
            account = account,
            dialogId = dialogId,
            replyToMsg = replyToMsg,
            replyToTopMsg = replyToTopMsg,
            prompt = finalPrompt,
            media = mediaFile,
            sendToChat = sendToChat,
            dialogTitle = "MediaTok AI"
        )
    }

    private fun runTldrCommand(
        account: Int,
        dialogId: Long,
        replyToTopMsg: MessageObject?,
        count: Int,
        sendToChat: Boolean
    ) {
        val messagesText = collectRecentDialogText(account, dialogId, count)
        if (messagesText.isBlank()) {
            toast("MediaTok: в кеше открытого чата недостаточно текста для пересказа")
            return
        }
        val prompt = """
            Сделай чёткий, структурированный TL;DR пересказ последних сообщений чата на русском языке:
            1) Главная тема и суть
            2) Ключевые факты, решения и договорённости (списком)
            3) Настроение и открытые вопросы
            
            Сообщения:
            $messagesText
        """.trimIndent()

        executeAi(
            account = account,
            dialogId = dialogId,
            replyToMsg = null,
            replyToTopMsg = replyToTopMsg,
            prompt = prompt,
            media = null,
            sendToChat = sendToChat,
            dialogTitle = "TL;DR ($count сообщ.)"
        )
    }

    private fun runTranslateCommand(
        account: Int,
        dialogId: Long,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        arg: String,
        sendToChat: Boolean
    ) {
        val parts = arg.split(Regex("\\s+"), limit = 2)
        val lang = parts.getOrNull(0)?.trim().orEmpty()
        val text = if (parts.size > 1) parts[1].trim() else extractText(replyToMsg)
        if (lang.isBlank() || text.isBlank()) {
            toast("MediaTok: формат .tr <язык> <текст> (или реплай)")
            return
        }
        runTextTransformCommand(
            account = account,
            dialogId = dialogId,
            replyToMsg = replyToMsg,
            replyToTopMsg = replyToTopMsg,
            instruction = "Переведи следующий текст на язык «$lang». Верни только точный перевод без комментариев:",
            text = text,
            sendToChat = sendToChat,
            dialogTitle = "Перевод ($lang)"
        )
    }

    private fun runTextTransformCommand(
        account: Int,
        dialogId: Long,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        instruction: String,
        text: String,
        sendToChat: Boolean,
        dialogTitle: String
    ) {
        val prompt = "$instruction\n\n$text"
        executeAi(account, dialogId, replyToMsg, replyToTopMsg, prompt, null, sendToChat, dialogTitle)
    }

    @JvmStatic
    fun transcribeMedia(fragment: BaseFragment?, account: Int, messageObject: MessageObject?) {
        if (messageObject == null) return
        ensureObservers(account)
        if (!messageObject.isVoice && !messageObject.isRoundVideo) {
            toast("MediaTok: поддерживаются голосовые сообщения и видеокружки")
            return
        }
        val path = FileLoader.getInstance(account).getPathToMessage(messageObject.messageOwner)
        if (path == null || !path.exists() || path.length() == 0L) {
            messageObject.document?.let { doc ->
                FileLoader.getInstance(account).loadFile(doc, messageObject, FileLoader.PRIORITY_HIGH, 0)
            }
            toast("MediaTok: скачиваю медиа... Нажмите «Расшифровать (AI)» через пару секунд")
            return
        }
        val mime = if (messageObject.isRoundVideo) "video/mp4" else "audio/ogg"
        val prompt = """
            Сделай точную дословную расшифровку (транскрипт) речи из этого аудио/видеосообщения.
            Если уместно, ниже добавь 1-2 строки краткого резюме. Отвечай на языке оригинала (или русском для комментария).
        """.trimIndent()
        executeAi(
            account = account,
            dialogId = messageObject.dialogId,
            replyToMsg = messageObject,
            replyToTopMsg = null,
            prompt = prompt,
            media = Pair(path, mime),
            sendToChat = false,
            dialogTitle = "AI Расшифровка"
        )
    }

    private fun executeAi(
        account: Int,
        dialogId: Long,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        prompt: String,
        media: Pair<File, String>?,
        sendToChat: Boolean,
        dialogTitle: String
    ) {
        val key = resolveApiKey()
        if (key.isBlank()) {
            AndroidUtilities.runOnUIThread {
                toast("MediaTok: задайте Gemini API ключ через .mtkey <KEY> или в Настройках MediaTok")
                openSettingsDialog(getActivity())
            }
            return
        }
        toast("MediaTok: запрос к AI...")
        Utilities.globalQueue.postRunnable {
            val result = try {
                callGemini(key, prompt, media)
            } catch (e: Throwable) {
                FileLog.e(e)
                "Ошибка AI: ${e.message ?: e.javaClass.simpleName}"
            }
            AndroidUtilities.runOnUIThread {
                if (sendToChat && !result.startsWith("Ошибка AI:")) {
                    sendTextReply(account, dialogId, result, replyToMsg, replyToTopMsg)
                } else {
                    showResultDialog(dialogTitle, result, account, dialogId, replyToMsg, replyToTopMsg)
                }
            }
        }
    }

    private fun callGemini(apiKey: String, prompt: String, media: Pair<File, String>?): String {
        val configuredModel = NaConfig.llmGeminiModel.String()?.trim().orEmpty()
        val model = if (configuredModel.isNotEmpty() && configuredModel.contains("gemini", ignoreCase = true)) {
            configuredModel
        } else {
            "gemini-2.5-flash"
        }

        val partsArray = JSONArray()
        if (media != null) {
            val (file, mime) = media
            if (!file.exists()) error("Файл медиа не найден")
            if (file.length() > MAX_INLINE_MEDIA_BYTES) error("Файл больше 16 МБ")
            val bytes = file.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            partsArray.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", mime)
                    put("data", b64)
                })
            })
        }
        partsArray.put(JSONObject().apply {
            put("text", prompt)
        })

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", partsArray)
            }))
        }.toString()

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) {
            val errMsg = try {
                JSONObject(respText).optJSONObject("error")?.optString("message").orEmpty()
            } catch (_: Throwable) {
                ""
            }
            error(if (errMsg.isNotBlank()) "HTTP $code: $errMsg" else "HTTP $code")
        }
        val json = JSONObject(respText)
        val candidates = json.optJSONArray("candidates") ?: error("Пустой ответ от Gemini")
        if (candidates.length() == 0) error("Gemini не вернул вариантов")
        val contentParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: error("Нет текста в ответе Gemini")
        val sb = StringBuilder()
        for (i in 0 until contentParts.length()) {
            val t = contentParts.getJSONObject(i).optString("text")
            if (!t.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(t)
            }
        }
        return sb.toString().trim().ifEmpty { "Пустой ответ" }
    }

    private fun sendTextReply(
        account: Int,
        dialogId: Long,
        text: String,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?
    ) {
        val safeText = if (text.startsWith(".")) " $text" else text
        val p = SendMessagesHelper.SendMessageParams.of(safeText, dialogId)
        p.replyToMsg = replyToMsg
        p.replyToTopMsg = replyToTopMsg
        SendMessagesHelper.getInstance(account).sendMessage(p)
    }

    private fun resolveApiKey(): String {
        val local = prefs().getString(KEY_GEMINI_API, "")?.trim().orEmpty()
        if (local.isNotEmpty()) return local
        val nagramKeys = NaConfig.llmApiKeys.String()?.split(",")?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }.orEmpty()
        return nagramKeys
    }

    private fun resolveCommandText(arg: String, replyToMsg: MessageObject?): String {
        return if (arg.isNotBlank()) arg else extractText(replyToMsg)
    }

    private fun extractText(msg: MessageObject?): String {
        if (msg == null) return ""
        val ownerMsg = msg.messageOwner?.message?.trim().orEmpty()
        if (ownerMsg.isNotEmpty()) return ownerMsg
        val cap = msg.caption?.toString()?.trim().orEmpty()
        if (cap.isNotEmpty()) return cap
        return msg.messageText?.toString()?.trim().orEmpty()
    }

    private fun buildReplyChain(account: Int, start: MessageObject?): String {
        if (start == null) return ""
        val lines = ArrayList<String>()
        var cur: MessageObject? = start
        var steps = 0
        while (cur != null && steps < 5) {
            val text = extractText(cur)
            if (text.isNotBlank()) {
                val author = resolveSenderName(account, cur)
                lines.add("[$author]: $text")
            }
            cur = cur.replyMessageObject
            steps++
        }
        return lines.asReversed().joinToString("\n")
    }

    private fun findReplyMedia(account: Int, reply: MessageObject?): Pair<File, String>? {
        if (reply == null) return null
        val mime = when {
            reply.isPhoto -> "image/jpeg"
            reply.isVoice -> "audio/ogg"
            reply.isRoundVideo -> "video/mp4"
            else -> return null
        }
        val file = FileLoader.getInstance(account).getPathToMessage(reply.messageOwner) ?: return null
        if (!file.exists() || file.length() <= 0L) return null
        return Pair(file, mime)
    }

    private fun collectRecentDialogText(account: Int, dialogId: Long, limit: Int): String {
        val fragment = LaunchActivity.getLastFragment()
        val list = if (fragment is ChatActivity && fragment.dialogId == dialogId) {
            fragment.messages
        } else {
            null
        } ?: return ""

        val lines = ArrayList<String>()
        for (i in list.indices) {
            if (lines.size >= limit) break
            val m = list[i]
            val t = extractText(m)
            if (t.isNotBlank()) {
                lines.add("${resolveSenderName(account, m)}: $t")
            }
        }
        return lines.asReversed().joinToString("\n")
    }

    private fun resolveSenderName(account: Int, msg: MessageObject): String {
        val senderId = msg.senderId
        if (senderId == UserConfig.getInstance(account).clientUserId) return "Вы"
        if (senderId > 0L) {
            val u = MessagesController.getInstance(account).getUser(senderId)
            if (u != null) {
                val full = listOfNotNull(u.first_name, u.last_name).filter { it.isNotBlank() }.joinToString(" ")
                return full.ifBlank { u.username ?: "User" }
            }
        } else if (senderId < 0L) {
            val c = MessagesController.getInstance(account).getChat(-senderId)
            if (c != null && !c.title.isNullOrBlank()) return c.title
        }
        return "Собеседник"
    }

    private fun parseDurationSeconds(raw: String): Int? {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        val match = Regex("^(\\d+)([smhd]?)$").matchEntire(s) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2]) {
            "", "s" -> num
            "m" -> num * 60
            "h" -> num * 3600
            "d" -> num * 86400
            else -> null
        }
    }

    private fun saveWipePeers() {
        val set = wipePeers.map { it.toString() }.toSet()
        prefs().edit().putStringSet(KEY_WIPE_PEERS, set).apply()
    }

    private fun showHelpDialog(account: Int, dialogId: Long) {
        AndroidUtilities.runOnUIThread {
            val activity = getActivity() ?: return@runOnUIThread
            val autoSec = autoDeletePeers[dialogId] ?: 0
            val wipe = wipePeers.contains(dialogId)
            val keySet = resolveApiKey().isNotBlank()
            val text = buildString {
                append("AI (Gemini): ").append(if (keySet) "Ключ задан" else "Ключ НЕ задан").append("\n")
                append("Авто-удаление в чате: ").append(if (autoSec > 0) "${autoSec}s" else "Выкл").append("\n")
                append("Wipe входящих в ЛС: ").append(if (wipe) "Вкл" else "Выкл").append("\n\n")
                append("Команды в чате:\n")
                append("• .ai <вопрос> — ответ окном (+ реплай/фото/ГС/кружок)\n")
                append("• .ai! <вопрос> — ответ сразу в чат\n")
                append("• .tldr [N] / .tldr! — пересказ последних N сообщений\n")
                append("• .tr <язык> <текст> (или .tr!) — перевод\n")
                append("• .fix <текст> (или .fix!) — исправить грамматику\n")
                append("• .tone <стиль> <текст> (или .tone!) — сменить тон\n")
                append("• .autodel <10s|1m|1h|off|status> — таймер своих сообщений\n")
                append("• .delme [N|10m|2h] — удалить свои сообщения\n")
                append("• .wipe / .unwipe — авто-удаление входящих в ЛС\n")
                append("• .mtkey <API_KEY> — быстро сохранить Gemini ключ\n")
                append("• .mt / .help — эта шпаргалка")
            }
            AlertDialog.Builder(activity)
                .setTitle("MediaTok — Команды и AI")
                .setMessage(text)
                .setNeutralButton("Настройки AI") { _, _ -> openSettingsDialog(activity) }
                .setPositiveButton("Копировать") { _, _ ->
                    AndroidUtilities.addToClipboard(text)
                    toast("MediaTok: шпаргалка скопирована")
                }
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .show()
        }
    }

    @JvmStatic
    fun openSettingsDialog(context: Context?) {
        val ctx = context ?: getActivity() ?: return
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20)
            setPadding(p, AndroidUtilities.dp(10), p, AndroidUtilities.dp(6))
        }
        val info = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            text = "Gemini API ключ (можно получить в Google AI Studio). Если оставить пустым — используется ключ из Настроек перевода (LLM):"
        }
        layout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8))

        val input = EditTextBoldCursor(ctx).apply {
            hint = "AIza..."
            setText(prefs().getString(KEY_GEMINI_API, ""))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        AlertDialog.Builder(ctx)
            .setTitle("MediaTok AI и Утилиты")
            .setView(layout)
            .setNeutralButton("Сброс .autodel/.wipe") { _, _ ->
                autoDeletePeers.clear()
                wipePeers.clear()
                val editor = prefs().edit().remove(KEY_WIPE_PEERS)
                for (k in prefs().all.keys) {
                    if (k.startsWith(PREFIX_AUTODEL)) editor.remove(k)
                }
                editor.apply()
                toast("MediaTok: все правила авто-удаления сброшены")
            }
            .setPositiveButton(LocaleController.getString(R.string.Save)) { _, _ ->
                prefs().edit().putString(KEY_GEMINI_API, input.text?.toString()?.trim().orEmpty()).apply()
                toast("MediaTok: настройки сохранены")
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    @JvmStatic
    fun openPeerNoteDialog(context: Context?, peerId: Long, peerTitle: String?) {
        val ctx = context ?: getActivity() ?: return
        if (peerId == 0L) return
        val key = PREFIX_NOTE + peerId
        val existing = prefs().getString(key, "").orEmpty()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20)
            setPadding(p, AndroidUtilities.dp(10), p, AndroidUtilities.dp(6))
        }
        val input = EditTextBoldCursor(ctx).apply {
            hint = "Личное досье / заметка о собеседнике (хранится локально)..."
            setText(existing)
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            isSingleLine = false
        }
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        AlertDialog.Builder(ctx)
            .setTitle("Заметка MediaTok${if (!peerTitle.isNullOrBlank()) ": $peerTitle" else ""}")
            .setView(layout)
            .setNeutralButton(LocaleController.getString(R.string.Clear)) { _, _ ->
                prefs().edit().remove(key).apply()
                toast("Заметка очищена")
            }
            .setPositiveButton(LocaleController.getString(R.string.Save)) { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) prefs().edit().remove(key).apply()
                else prefs().edit().putString(key, value).apply()
                toast("Заметка сохранена")
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun showResultDialog(
        title: String,
        body: String,
        account: Int,
        dialogId: Long,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?
    ) {
        val activity = getActivity()
        if (activity == null) {
            toast(body)
            return
        }
        val scroll = ScrollView(activity).apply {
            val p = AndroidUtilities.dp(20)
            setPadding(p, AndroidUtilities.dp(8), p, AndroidUtilities.dp(4))
        }
        val tv = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextIsSelectable(true)
            text = body
        }
        scroll.addView(tv)

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNeutralButton("В чат") { _, _ ->
                sendTextReply(account, dialogId, body, replyToMsg, replyToTopMsg)
            }
            .setPositiveButton("Копировать") { _, _ ->
                AndroidUtilities.addToClipboard(body)
                toast("Скопировано")
            }
            .setNegativeButton(LocaleController.getString(R.string.Close), null)
            .show()
    }

    private fun getActivity(): Activity? {
        return LaunchActivity.getLastFragment()?.parentActivity
    }

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            Toast.makeText(ApplicationLoader.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
