package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object MediaTokWavesHelper {

    private const val PREFS_NAME = "mediatok_prefs"
    private const val KEY_NOVOICE_ENABLED = "mt_w_novoice_on"
    private const val KEY_NOVOICE_REPLY = "mt_w_novoice_reply"
    private const val KEY_FROZEN_CHATS = "mt_w_frozen_chats"
    private const val KEY_MACROS_JSON = "mt_w_macros_json"
    private const val KEY_MIRROR_CHATS = "mt_w_mirror_chats"
    private const val KEY_CAPTCHA_ENABLED = "mt_w_captcha_on"
    private const val KEY_CAPTCHA_VERIFIED = "mt_w_captcha_verified"
    private const val KEY_CLANTAG = "mt_w_clantag"
    private const val KEY_PROMOSNIPE_ENABLED = "mt_w_promosnipe_on"
    private const val KEY_FIRSTCOM_ENABLED = "mt_w_firstcom_on"
    private const val KEY_FIRSTCOM_TEMPLATE = "mt_w_firstcom_tpl"
    private const val KEY_VIP_FRIENDS = "mt_w_vip_friends"

    private const val DEFAULT_NOVOICE_REPLY =
        "Извини, сейчас не могу слушать голосовые — напиши, пожалуйста, текстом"
    private const val DEFAULT_FIRSTCOM_TEMPLATE =
        "{Первый!|Топ!|Огонь!|Ждали этот пост!} {🔥|⚡|👏|💯}"

    private val frozenChats = CopyOnWriteArraySet<Long>()
    private val mirrorChats = CopyOnWriteArraySet<Long>()
    private val vipFriends = CopyOnWriteArraySet<Long>()
    private val captchaVerified = CopyOnWriteArraySet<Long>()
    private val macros = ConcurrentHashMap<String, String>()

    // Runtime state (transient)
    private val pendingGhost1m = ConcurrentHashMap<Long, Int>() // dialogId -> pending count
    private val pendingCaptchaAnswer = ConcurrentHashMap<Long, Int>() // dialogId -> expected math sum
    private val lastAutoReplyMs = ConcurrentHashMap<Long, Long>() // anti-flood cooldown per dialog
    private val lastFirstComMs = ConcurrentHashMap<Long, Long>() // anti-flood cooldown per discussion
    private val random = Random()

    @Volatile
    private var loaded = false

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        val sp = prefs()
        sp.getStringSet(KEY_FROZEN_CHATS, emptySet())?.forEach { it.toLongOrNull()?.let(frozenChats::add) }
        sp.getStringSet(KEY_MIRROR_CHATS, emptySet())?.forEach { it.toLongOrNull()?.let(mirrorChats::add) }
        sp.getStringSet(KEY_VIP_FRIENDS, emptySet())?.forEach { it.toLongOrNull()?.let(vipFriends::add) }
        sp.getStringSet(KEY_CAPTCHA_VERIFIED, emptySet())?.forEach { it.toLongOrNull()?.let(captchaVerified::add) }
        val rawMacros = sp.getString(KEY_MACROS_JSON, "").orEmpty()
        if (rawMacros.isNotBlank()) {
            try {
                val obj = JSONObject(rawMacros)
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.optString(k, "")
                    if (k.isNotBlank() && v.isNotBlank()) macros[k] = v
                }
            } catch (_: Throwable) {
            }
        }
        loaded = true
    }

    private fun saveLongSet(key: String, set: Set<Long>) {
        prefs().edit().putStringSet(key, set.map { it.toString() }.toSet()).apply()
    }

    private fun saveMacros() {
        val obj = JSONObject()
        for ((k, v) in macros) obj.put(k, v)
        prefs().edit().putString(KEY_MACROS_JSON, obj.toString()).apply()
    }

    fun isNoVoiceEnabled(): Boolean = prefs().getBoolean(KEY_NOVOICE_ENABLED, false)
    fun isCaptchaEnabled(): Boolean = prefs().getBoolean(KEY_CAPTCHA_ENABLED, false)
    fun isPromoSnipeEnabled(): Boolean = prefs().getBoolean(KEY_PROMOSNIPE_ENABLED, false)
    fun isFirstComEnabled(): Boolean = prefs().getBoolean(KEY_FIRSTCOM_ENABLED, false)
    fun getClanTag(): String = prefs().getString(KEY_CLANTAG, "").orEmpty()

    // MARK: - Outgoing Normal Message Interceptor (FreezeChat, Macros, #992 Broken URL Joiner)

    @JvmStatic
    fun interceptOrTransformNormalMessage(
        activity: Activity?,
        account: Int,
        params: SendMessagesHelper.SendMessageParams
    ): Boolean {
        ensureLoaded()
        val dialogId = params.peer
        val raw = params.message ?: return false

        // 1. Freeze Chat: block accidental normal sends (commands starting with '.' still pass to unfreeze!)
        if (frozenChats.contains(dialogId) && !raw.trim().startsWith(".")) {
            toast("MediaTok: чат заморожен (.freezechat чтобы разморозить)")
            return true
        }

        // Skip transformations for dot-commands
        if (raw.trim().startsWith(".")) return false

        var updated = raw

        // 2. Macros expansion (;abbr)
        if (macros.isNotEmpty() && updated.contains(";")) {
            val tokenRegex = Regex("(?<=^|\\s);([a-zA-Zа-яА-Я0-9_]{1,24})(?=\\s|$|[.,!?;:])")
            updated = tokenRegex.replace(updated) { m ->
                val key = m.groupValues[1].lowercase(Locale.ROOT)
                macros[key] ?: m.value
            }
        }

        // 3. #992 Broken multi-line URL joiner (rejoins wrapped URLs split across newlines)
        if (updated.contains("http://") || updated.contains("https://")) {
            val brokenUrlRegex = Regex("(https?://[^\\s]+)\\r?\\n([A-Za-z0-9_\\-./?=&%#+~:;@]+)")
            var prev: String
            var guard = 0
            do {
                prev = updated
                updated = brokenUrlRegex.replace(updated) { m ->
                    val tail = m.groupValues[2]
                    if (tail.startsWith("http://") || tail.startsWith("https://")) {
                        m.value
                    } else {
                        m.groupValues[1] + tail
                    }
                }
                guard++
            } while (updated != prev && guard < 4)
        }

        if (updated != raw) {
            params.message = updated
        }
        return false
    }

    // MARK: - NotificationCenter Hooks (Outgoing Ghost1m & Incoming Automations)

    @JvmStatic
    fun onOutgoingSent(account: Int, dialogId: Long, newMsgId: Int) {
        if (newMsgId <= 0) return
        val remaining = pendingGhost1m[dialogId] ?: return
        if (remaining <= 0) {
            pendingGhost1m.remove(dialogId)
            return
        }
        if (remaining == 1) pendingGhost1m.remove(dialogId) else pendingGhost1m[dialogId] = remaining - 1
        AndroidUtilities.runOnUIThread({
            try {
                MessagesController.getInstance(account)
                    .deleteMessages(arrayListOf(newMsgId), null, null, dialogId, 0, true, 0)
            } catch (e: Throwable) {
                FileLog.e(e)
            }
        }, 60_000L)
    }

    @JvmStatic
    fun onIncomingMessages(account: Int, dialogId: Long, messages: ArrayList<MessageObject>) {
        ensureLoaded()
        if (messages.isEmpty()) return
        val now = System.currentTimeMillis()
        val isUserDm = dialogId > 0L && DialogObject.isUserDialog(dialogId)
        val selfId = UserConfig.getInstance(account).getClientUserId()
        if (dialogId == selfId) return

        val mc = MessagesController.getInstance(account)
        val peerUser = if (isUserDm) mc.getUser(dialogId) else null
        val isVip = vipFriends.contains(dialogId)

        for (m in messages) {
            if (m.isOut || m.id <= 0 || m.scheduled) continue
            val text = m.messageText?.toString()?.trim().orEmpty()

            // 1. Promo Code Sniper (works in all chats & channels)
            if (isPromoSnipeEnabled() && text.length >= 6) {
                extractPromoCode(text)?.let { code ->
                    AndroidUtilities.runOnUIThread {
                        copyText("MediaTok Promo", code)
                        toast("PromoSnipe: скопирован промокод $code")
                    }
                }
            }

            // 2. Auto-First-Comment Sniper (channel post forwarded into discussion supergroup)
            if (isFirstComEnabled() && DialogObject.isChatDialog(dialogId)) {
                val owner = m.messageOwner
                val isChannelPostInDiscussion = owner != null &&
                    owner.fwd_from != null &&
                    owner.fwd_from.channel_post > 0 &&
                    owner.reply_to == null
                if (isChannelPostInDiscussion) {
                    val last = lastFirstComMs[dialogId] ?: 0L
                    if (now - last > 15_000L) {
                        lastFirstComMs[dialogId] = now
                        val tpl = prefs().getString(KEY_FIRSTCOM_TEMPLATE, DEFAULT_FIRSTCOM_TEMPLATE)
                            .orEmpty().ifBlank { DEFAULT_FIRSTCOM_TEMPLATE }
                        val replyText = expandSpinSyntax(tpl)
                        AndroidUtilities.runOnUIThread({
                            sendReply(account, dialogId, replyText, m, notify = true)
                        }, 350L)
                    }
                }
            }

            // 3. DM-only automations
            if (isUserDm && peerUser != null && !peerUser.bot) {
                // 3a. Anti-Spam DM Captcha for unknown non-contacts
                if (isCaptchaEnabled() && !peerUser.contact && !peerUser.verified && !isVip && !captchaVerified.contains(dialogId)) {
                    val expected = pendingCaptchaAnswer[dialogId]
                    if (expected != null) {
                        val cleanedNum = Regex("\\d+").find(text)?.value?.toIntOrNull()
                        if (cleanedNum == expected) {
                            pendingCaptchaAnswer.remove(dialogId)
                            captchaVerified.add(dialogId)
                            saveLongSet(KEY_CAPTCHA_VERIFIED, captchaVerified)
                            AndroidUtilities.runOnUIThread {
                                sendText(account, dialogId, "✅ Проверка пройдена! Можешь писать.", notify = true)
                            }
                        }
                    } else {
                        val last = lastAutoReplyMs[dialogId] ?: 0L
                        if (now - last > 30_000L) {
                            lastAutoReplyMs[dialogId] = now
                            val a = 2 + random.nextInt(8)
                            val b = 2 + random.nextInt(8)
                            pendingCaptchaAnswer[dialogId] = a + b
                            AndroidUtilities.runOnUIThread({
                                sendText(
                                    account,
                                    dialogId,
                                    "🛡 MediaTok Anti-Spam: чтобы написать, реши пример: $a + $b = ?",
                                    notify = true
                                )
                            }, 500L)
                        }
                    }
                }

                // 3b. NoVoice auto-reply on incoming voice messages
                if (isNoVoiceEnabled() && !isVip && m.isVoice()) {
                    val last = lastAutoReplyMs[dialogId] ?: 0L
                    if (now - last > 20_000L) {
                        lastAutoReplyMs[dialogId] = now
                        val reply = prefs().getString(KEY_NOVOICE_REPLY, DEFAULT_NOVOICE_REPLY)
                            .orEmpty().ifBlank { DEFAULT_NOVOICE_REPLY }
                        AndroidUtilities.runOnUIThread({
                            sendReply(account, dialogId, reply, m, notify = true)
                        }, 600L)
                    }
                }

                // 3c. Mirror Mode in DM
                if (mirrorChats.contains(dialogId) && text.isNotEmpty() && !text.startsWith(".")) {
                    val last = lastAutoReplyMs[dialogId] ?: 0L
                    if (now - last > 1_200L) {
                        lastAutoReplyMs[dialogId] = now
                        AndroidUtilities.runOnUIThread({
                            sendText(account, dialogId, text, notify = true)
                        }, 450L)
                    }
                }
            }
        }
    }

    // MARK: - Command Router

    @JvmStatic
    fun handleCommand(
        activity: Activity?,
        account: Int,
        dialogId: Long,
        cmdLower: String,
        arg: String,
        params: SendMessagesHelper.SendMessageParams
    ): Boolean {
        ensureLoaded()
        return when (cmdLower) {
            ".waves", ".mt2" -> {
                AndroidUtilities.runOnUIThread { openWavesHubDialog(activity, account, dialogId) }
                true
            }
            ".breach" -> {
                if (arg.isBlank()) {
                    AndroidUtilities.runOnUIThread { openBreachPromptDialog(activity) }
                } else {
                    runBreachCheck(activity, arg)
                }
                true
            }
            ".novoice" -> {
                handleNoVoiceCommand(arg)
                true
            }
            ".freezechat" -> {
                if (frozenChats.contains(dialogId)) {
                    frozenChats.remove(dialogId)
                    toast("MediaTok: чат разморожен")
                } else {
                    frozenChats.add(dialogId)
                    toast("MediaTok: чат заморожен (отправка заблокирована)")
                }
                saveLongSet(KEY_FROZEN_CHATS, frozenChats)
                true
            }
            ".macros", ".macro" -> {
                handleMacrosCommand(activity, arg)
                true
            }
            ".ghost1m" -> {
                if (arg.isBlank()) {
                    toast("MediaTok: формат .ghost1m <текст>")
                } else {
                    pendingGhost1m[dialogId] = (pendingGhost1m[dialogId] ?: 0) + 1
                    AndroidUtilities.runOnUIThread {
                        sendText(account, dialogId, arg, notify = false)
                        toast("Ghost1m: отправлено тихо, удалится через 60с")
                    }
                }
                true
            }
            ".mirror" -> {
                if (dialogId <= 0L || !DialogObject.isUserDialog(dialogId)) {
                    toast("MediaTok: .mirror работает только в личных чатах")
                } else if (mirrorChats.contains(dialogId)) {
                    mirrorChats.remove(dialogId)
                    saveLongSet(KEY_MIRROR_CHATS, mirrorChats)
                    toast("MediaTok: Mirror Mode ВЫКЛ")
                } else {
                    mirrorChats.add(dialogId)
                    saveLongSet(KEY_MIRROR_CHATS, mirrorChats)
                    toast("MediaTok: Mirror Mode ВКЛ для этого чата")
                }
                true
            }
            ".captcha" -> {
                val next = when (arg.lowercase(Locale.ROOT)) {
                    "on", "1", "вкл" -> true
                    "off", "0", "выкл" -> false
                    else -> !isCaptchaEnabled()
                }
                prefs().edit().putBoolean(KEY_CAPTCHA_ENABLED, next).apply()
                toast("MediaTok Anti-Spam Captcha: ${if (next) "ВКЛ" else "ВЫКЛ"}")
                true
            }
            ".clantag" -> {
                handleClanTagCommand(account, arg)
                true
            }
            ".ton", ".tonrate" -> {
                runTonRateLookup(activity)
                true
            }
            ".walletlink", ".tonwallet" -> {
                if (arg.isBlank()) {
                    AndroidUtilities.runOnUIThread { openWalletPromptDialog(activity) }
                } else {
                    runTonWalletBalance(activity, arg.trim())
                }
                true
            }
            ".purgeme" -> {
                MediaTokHelper.runPurgeMeFromExternal(account, dialogId, arg.ifBlank { "200" })
                true
            }
            ".otccheck" -> {
                runOtcCheck(activity, account, dialogId)
                true
            }
            ".snipeuser" -> {
                if (arg.isBlank()) {
                    AndroidUtilities.runOnUIThread { openSnipeUserPromptDialog(activity) }
                } else {
                    runSnipeUserCheck(activity, arg)
                }
                true
            }
            ".promosnipe" -> {
                val next = when (arg.lowercase(Locale.ROOT)) {
                    "on", "1", "вкл" -> true
                    "off", "0", "выкл" -> false
                    else -> !isPromoSnipeEnabled()
                }
                prefs().edit().putBoolean(KEY_PROMOSNIPE_ENABLED, next).apply()
                toast("MediaTok PromoSnipe: ${if (next) "ВКЛ" else "ВЫКЛ"}")
                true
            }
            ".firstcom" -> {
                handleFirstComCommand(arg)
                true
            }
            ".nftmarket" -> {
                val q = arg.trim()
                val url = if (q.isBlank()) {
                    "https://fragment.com/gifts"
                } else {
                    "https://fragment.com/gifts?query=" + URLEncoder.encode(q, "UTF-8")
                }
                openUrl(activity, url)
                true
            }
            ".vipfriends", ".vip" -> {
                handleVipFriendsCommand(activity, account, dialogId, arg)
                true
            }
            ".nukeall" -> {
                AndroidUtilities.runOnUIThread { openNukeConfirmDialog(activity, account, dialogId) }
                true
            }
            else -> false
        }
    }

    // MARK: - Interactive Waves 19–25 Hub

    @JvmStatic
    fun openWavesHubDialog(activity: Activity?, account: Int, dialogId: Long) {
        ensureLoaded()
        val ctx = activity ?: return

        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20f)
            setPadding(p, AndroidUtilities.dp(10f), p, AndroidUtilities.dp(12f))
        }
        scroll.addView(root)

        val status = TextView(ctx).apply {
            val tag = getClanTag().ifBlank { "—" }
            text = buildString {
                append("NoVoice: ").append(if (isNoVoiceEnabled()) "ВКЛ" else "выкл")
                append("  •  Captcha: ").append(if (isCaptchaEnabled()) "ВКЛ" else "выкл")
                append("\nPromoSnipe: ").append(if (isPromoSnipeEnabled()) "ВКЛ" else "выкл")
                append("  •  FirstCom: ").append(if (isFirstComEnabled()) "ВКЛ" else "выкл")
                append("\nFreezeChat: ").append(if (frozenChats.contains(dialogId)) "ЗАМОРОЖЕН" else "открыт")
                append("  •  Mirror: ").append(if (mirrorChats.contains(dialogId)) "ВКЛ" else "выкл")
                append("\nClanTag: ").append(tag)
                append("  •  Макросов: ").append(macros.size)
                append("  •  VIP: ").append(vipFriends.size)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setPadding(0, 0, 0, AndroidUtilities.dp(10f))
        }
        root.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        var dialogRef: AlertDialog? = null

        fun addActionRow(title: String, onClick: () -> Unit) {
            val tv = TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText))
                setPadding(0, AndroidUtilities.dp(9f), 0, AndroidUtilities.dp(9f))
                isClickable = true
                setOnClickListener {
                    dialogRef?.dismiss()
                    onClick()
                }
            }
            root.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        addActionRow("• Курс TON в реальном времени (.ton)") { runTonRateLookup(ctx) }
        addActionRow("• Отправить TON перевод (.tonpay)") { MediaTokPowerHelper.openTonPayDialog(ctx, "") }
        addActionRow("• Проверить баланс TON кошелька (.walletlink)") { openWalletPromptDialog(ctx) }
        addActionRow("• Проверить пароль на утечки HIBP (.breach)") { openBreachPromptDialog(ctx) }
        addActionRow("• Проверить собеседника на OTC фейк-био (.otccheck)") { runOtcCheck(ctx, account, dialogId) }
        addActionRow("• Проверить доступность @username (.snipeuser)") { openSnipeUserPromptDialog(ctx) }
        addActionRow("• Редактор макросов ;abbr (.macros)") { openMacrosDialog(ctx) }
        addActionRow("• Интеграции профиля Discord-style (.integr)") {
            MediaTokIntegrationsHelper.openIntegrationsDialog(ctx, account, UserConfig.getInstance(account).getClientUserId())
        }
        addActionRow("• Переключить NoVoice (${if (isNoVoiceEnabled()) "ВКЛ" else "выкл"})") { handleNoVoiceCommand("") }
        addActionRow("• Переключить Anti-Spam Captcha (${if (isCaptchaEnabled()) "ВКЛ" else "выкл"})") {
            prefs().edit().putBoolean(KEY_CAPTCHA_ENABLED, !isCaptchaEnabled()).apply()
            toast("MediaTok Captcha: ${if (isCaptchaEnabled()) "ВКЛ" else "ВЫКЛ"}")
        }
        addActionRow("• Переключить PromoSnipe (${if (isPromoSnipeEnabled()) "ВКЛ" else "выкл"})") {
            prefs().edit().putBoolean(KEY_PROMOSNIPE_ENABLED, !isPromoSnipeEnabled()).apply()
            toast("MediaTok PromoSnipe: ${if (isPromoSnipeEnabled()) "ВКЛ" else "ВЫКЛ"}")
        }
        addActionRow("• Переключить FirstCom снайпер (${if (isFirstComEnabled()) "ВКЛ" else "выкл"})") {
            handleFirstComCommand("")
        }
        addActionRow("• Заморозить / разморозить чат (.freezechat)") {
            if (frozenChats.contains(dialogId)) frozenChats.remove(dialogId) else frozenChats.add(dialogId)
            saveLongSet(KEY_FROZEN_CHATS, frozenChats)
            toast(if (frozenChats.contains(dialogId)) "Чат заморожен" else "Чат разморожен")
        }
        addActionRow("• Добавить/убрать чат в VIP Friends (.vipfriends)") {
            handleVipFriendsCommand(ctx, account, dialogId, "")
        }
        addActionRow("• Открыть Fragment NFT Gifts (.nftmarket)") { openUrl(ctx, "https://fragment.com/gifts") }
        addActionRow("• Экстренный сброс MediaTok (.nukeall)") { openNukeConfirmDialog(ctx, account, dialogId) }

        dialogRef = AlertDialog.Builder(ctx)
            .setTitle("MediaTok Waves 19–25 Hub")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .create()
        dialogRef.show()
    }

    // MARK: - Feature Implementations

    private fun handleNoVoiceCommand(arg: String) {
        val clean = arg.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (lower.startsWith("reply ")) {
            val custom = clean.substring(6).trim()
            if (custom.isNotBlank()) {
                prefs().edit()
                    .putBoolean(KEY_NOVOICE_ENABLED, true)
                    .putString(KEY_NOVOICE_REPLY, custom)
                    .apply()
                toast("MediaTok NoVoice ВКЛ с новым автоответом")
                return
            }
        }
        val next = when (lower) {
            "on", "1", "вкл" -> true
            "off", "0", "выкл" -> false
            else -> !isNoVoiceEnabled()
        }
        prefs().edit().putBoolean(KEY_NOVOICE_ENABLED, next).apply()
        toast("MediaTok NoVoice: ${if (next) "ВКЛ" else "ВЫКЛ"}")
    }

    private fun handleFirstComCommand(arg: String) {
        val clean = arg.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (lower.startsWith("text ")) {
            val tpl = clean.substring(5).trim()
            if (tpl.isNotBlank()) {
                prefs().edit()
                    .putBoolean(KEY_FIRSTCOM_ENABLED, true)
                    .putString(KEY_FIRSTCOM_TEMPLATE, tpl)
                    .apply()
                toast("MediaTok FirstCom ВКЛ (шаблон обновлён)")
                return
            }
        }
        val next = when (lower) {
            "on", "1", "вкл" -> true
            "off", "0", "выкл" -> false
            else -> !isFirstComEnabled()
        }
        prefs().edit().putBoolean(KEY_FIRSTCOM_ENABLED, next).apply()
        toast("MediaTok FirstCom: ${if (next) "ВКЛ" else "ВЫКЛ"}")
    }

    private fun handleClanTagCommand(account: Int, arg: String) {
        val clean = arg.trim()
        if (clean.isBlank()) {
            val cur = getClanTag()
            toast(if (cur.isBlank()) "MediaTok ClanTag не задан (.clantag TAG)" else "MediaTok ClanTag: [$cur]")
            return
        }
        if (clean.equals("off", true) || clean.equals("clear", true)) {
            prefs().edit().remove(KEY_CLANTAG).apply()
            publishClanTagCloud(UserConfig.getInstance(account).getClientUserId(), "")
            toast("MediaTok ClanTag очищен")
            return
        }
        val tag = clean.uppercase(Locale.ROOT).take(8)
        prefs().edit().putString(KEY_CLANTAG, tag).apply()
        publishClanTagCloud(UserConfig.getInstance(account).getClientUserId(), tag)
        toast("MediaTok ClanTag [$tag] сохранён и синхронизирован")
    }

    private fun publishClanTagCloud(uid: Long, tag: String) {
        if (uid <= 0L) return
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val payload = JSONObject().apply {
                    put("v", 1)
                    put("type", "clantag")
                    put("uid", uid)
                    put("tag", tag)
                    put("ts", System.currentTimeMillis() / 1000L)
                }
                conn = (URL("https://ntfy.sh/mtprof_v1_$uid").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Title", "mtprof_v1_clantag")
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (_: Throwable) {
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun handleMacrosCommand(activity: Activity?, arg: String) {
        val clean = arg.trim()
        if (clean.isBlank() || clean.equals("list", true)) {
            AndroidUtilities.runOnUIThread { openMacrosDialog(activity) }
            return
        }
        val parts = clean.split(Regex("\\s+"), limit = 3)
        when (parts[0].lowercase(Locale.ROOT)) {
            "add" -> {
                val key = parts.getOrNull(1)?.removePrefix(";")?.lowercase(Locale.ROOT).orEmpty()
                val value = parts.getOrNull(2)?.trim().orEmpty()
                if (key.isBlank() || value.isBlank()) {
                    toast("Формат: .macros add <код> <текст>")
                } else {
                    macros[key] = value
                    saveMacros()
                    toast("Макрос ;$key сохранён")
                }
            }
            "del", "rm" -> {
                val key = parts.getOrNull(1)?.removePrefix(";")?.lowercase(Locale.ROOT).orEmpty()
                if (macros.remove(key) != null) {
                    saveMacros()
                    toast("Макрос ;$key удалён")
                } else {
                    toast("Макрос ;$key не найден")
                }
            }
            else -> AndroidUtilities.runOnUIThread { openMacrosDialog(activity) }
        }
    }

    private fun openMacrosDialog(activity: Activity?) {
        val ctx = activity ?: return
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20f)
            setPadding(p, AndroidUtilities.dp(10f), p, AndroidUtilities.dp(6f))
        }
        val existingText = if (macros.isEmpty()) {
            "Пока пусто. Пример: введите код 'brb' и текст 'Буду через 5 минут', затем пишите ;brb в любом чате."
        } else {
            macros.entries.sortedBy { it.key }.joinToString("\n") { ";${it.key}  →  ${it.value}" }
        }
        val listTv = TextView(ctx).apply {
            text = existingText
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setPadding(0, 0, 0, AndroidUtilities.dp(10f))
        }
        layout.addView(listTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val keyInput = EditTextBoldCursor(ctx).apply {
            hint = "Код без ';' (например brb)"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            isSingleLine = true
        }
        layout.addView(keyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val valInput = EditTextBoldCursor(ctx).apply {
            hint = "Полный текст замены"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            minLines = 2
            maxLines = 4
        }
        layout.addView(valInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))

        AlertDialog.Builder(ctx)
            .setTitle("Макросы ;abbr (MediaTok)")
            .setView(layout)
            .setNeutralButton("Очистить все") { _, _ ->
                macros.clear()
                saveMacros()
                toast("Все макросы очищены")
            }
            .setPositiveButton("Сохранить") { _, _ ->
                val k = keyInput.text?.toString()?.trim()?.removePrefix(";")?.lowercase(Locale.ROOT).orEmpty()
                val v = valInput.text?.toString()?.trim().orEmpty()
                if (k.isNotBlank() && v.isNotBlank()) {
                    macros[k] = v
                    saveMacros()
                    toast("Макрос ;$k сохранён")
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openBreachPromptDialog(activity: Activity?) {
        val ctx = activity ?: return
        val input = EditTextBoldCursor(ctx).apply {
            hint = "Введите пароль для проверки HIBP (k-Anonymity)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            isSingleLine = true
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20f)
            setPadding(p, AndroidUtilities.dp(10f), p, AndroidUtilities.dp(4f))
            addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        AlertDialog.Builder(ctx)
            .setTitle("Проверка утечек пароля (.breach)")
            .setView(box)
            .setPositiveButton("Проверить") { _, _ ->
                val pwd = input.text?.toString().orEmpty()
                if (pwd.isNotEmpty()) runBreachCheck(ctx, pwd)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runBreachCheck(activity: Activity?, password: String) {
        toast("MediaTok: проверяю по HIBP (k-Anonymity)...")
        Thread {
            try {
                val digest = MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02X".format(it) }
                val prefix = hex.substring(0, 5)
                val suffix = hex.substring(5)

                val body = httpGet("https://api.pwnedpasswords.com/range/$prefix")
                var count = 0L
                for (line in body.lines()) {
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals(suffix, ignoreCase = true)) {
                        count = line.substring(idx + 1).trim().toLongOrNull() ?: 0L
                        break
                    }
                }
                val report = if (count > 0L) {
                    "⚠️ Пароль найден в базах утечек $count раз(а)!\n\nРекомендуется немедленно заменить его на уникальный."
                } else {
                    "✅ Пароль НЕ найден в известных базах утечек HaveIBeenPwned.\n\n(Передавались только первые 5 символов SHA-1 хэша.)"
                }
                showInfoDialog(activity, "HIBP Проверка пароля", report)
            } catch (e: Throwable) {
                toast("Ошибка HIBP: ${e.message ?: "нет сети"}")
            }
        }.start()
    }

    private fun runTonRateLookup(activity: Activity?) {
        toast("MediaTok: загружаю курс TON...")
        Thread {
            try {
                val json = JSONObject(
                    httpGet("https://api.coingecko.com/api/v3/simple/price?ids=the-open-network&vs_currencies=usd,rub&include_24hr_change=true")
                )
                val ton = json.optJSONObject("the-open-network")
                if (ton == null) {
                    toast("Не удалось получить курс TON")
                    return@Thread
                }
                val usd = ton.optDouble("usd", 0.0)
                val rub = ton.optDouble("rub", 0.0)
                val chg = ton.optDouble("usd_24h_change", 0.0)
                val sign = if (chg >= 0) "+" else ""
                val msg = String.format(
                    Locale.US,
                    "TON (The Open Network)\n\n• USD: $%.3f (%s%.2f%% за 24ч)\n• RUB: %.2f ₽",
                    usd, sign, chg, rub
                )
                showInfoDialog(activity, "Курс TON", msg)
            } catch (e: Throwable) {
                toast("Ошибка курса TON: ${e.message ?: "сеть недоступна"}")
            }
        }.start()
    }

    private fun openWalletPromptDialog(activity: Activity?) {
        val ctx = activity ?: return
        val input = EditTextBoldCursor(ctx).apply {
            hint = "TON адрес (UQ... / EQ...)"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            isSingleLine = true
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20f)
            setPadding(p, AndroidUtilities.dp(10f), p, AndroidUtilities.dp(4f))
            addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        AlertDialog.Builder(ctx)
            .setTitle("Баланс TON кошелька (.walletlink)")
            .setView(box)
            .setPositiveButton("Проверить") { _, _ ->
                val addr = input.text?.toString()?.trim().orEmpty()
                if (addr.isNotBlank()) runTonWalletBalance(ctx, addr)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runTonWalletBalance(activity: Activity?, address: String) {
        toast("MediaTok: запрашиваю баланс TON...")
        Thread {
            try {
                val enc = URLEncoder.encode(address, "UTF-8")
                val json = JSONObject(httpGet("https://toncenter.com/api/v2/getAddressBalance?address=$enc"))
                if (!json.optBoolean("ok", false)) {
                    toast("TonCenter: неверный адрес или лимит API")
                    return@Thread
                }
                val nano = json.optString("result", "0").toLongOrNull() ?: 0L
                val ton = nano / 1_000_000_000.0
                val body = String.format(
                    Locale.US,
                    "Адрес:\n%s\n\nБаланс: %.4f TON",
                    address, ton
                )
                AndroidUtilities.runOnUIThread {
                    val ctx = activity ?: return@runOnUIThread
                    AlertDialog.Builder(ctx)
                        .setTitle("TON Кошелёк")
                        .setMessage(body)
                        .setPositiveButton("Tonviewer") { _, _ -> openUrl(ctx, "https://tonviewer.com/$address") }
                        .setNeutralButton("Копировать") { _, _ ->
                            copyText("TON Address", address)
                            toast("Адрес скопирован")
                        }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            } catch (e: Throwable) {
                toast("Ошибка TonCenter: ${e.message ?: "нет сети"}")
            }
        }.start()
    }

    private fun runOtcCheck(activity: Activity?, account: Int, dialogId: Long) {
        if (dialogId <= 0L || !DialogObject.isUserDialog(dialogId)) {
            toast("MediaTok: .otccheck работает только в личном чате")
            return
        }
        val mc = MessagesController.getInstance(account)
        val user = mc.getUser(dialogId)
        if (user == null) {
            toast("MediaTok: пользователь не найден")
            return
        }
        val cachedFull = mc.getUserFull(dialogId)
        if (cachedFull != null) {
            evaluateOtcUser(activity, user, cachedFull.about.orEmpty())
            return
        }
        val req = TLRPC.TL_users_getFullUser().apply {
            id = mc.getInputUser(user)
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { response, _ ->
            val full = response as? TLRPC.TL_users_userFull
            val bio = full?.full_user?.about.orEmpty()
            evaluateOtcUser(activity, user, bio)
        }
    }

    private fun evaluateOtcUser(activity: Activity?, user: TLRPC.User, bio: String) {
        val realHandles = HashSet<String>()
        if (!user.username.isNullOrBlank()) realHandles.add(user.username.lowercase(Locale.ROOT))
        user.usernames?.forEach { u ->
            if (u.active && !u.username.isNullOrBlank()) realHandles.add(u.username.lowercase(Locale.ROOT))
        }
        val bioHandles = Regex("@([A-Za-z0-9_]{4,32})").findAll(bio)
            .map { it.groupValues[1].lowercase(Locale.ROOT) }
            .distinct()
            .toList()
        val suspicious = bioHandles.filter { !realHandles.contains(it) }

        val sb = StringBuilder()
        sb.append("ID: ").append(user.id).append("\n")
        sb.append("Настоящий @username: ")
            .append(if (realHandles.isEmpty()) "отсутствует" else realHandles.joinToString(", ") { "@$it" })
            .append("\n")
        sb.append("Скам/фейк флаги Telegram: ")
            .append(if (user.scam || user.fake) "⚠️ ДА (SCAM/FAKE)" else "нет")
            .append("\n\n")
        if (suspicious.isNotEmpty()) {
            sb.append("🚨 ВНИМАНИЕ (OTC РИСК)!\n")
            sb.append("В графе «О себе» указаны чужие/несовпадающие @username:\n")
            sb.append(suspicious.joinToString(", ") { "@$it" })
            sb.append("\n\nЧастая схема подмены гаранта: мошенник скрывает настоящий юзернейм и вписывает чужой в Bio.")
        } else {
            sb.append("✅ Подмены @username в Bio не обнаружено.")
            if (bio.isNotBlank()) {
                sb.append("\n\nBio: ").append(bio.take(180))
            }
        }
        showInfoDialog(activity, "OTC Анти-Скам Проверка", sb.toString())
    }

    private fun openSnipeUserPromptDialog(activity: Activity?) {
        val ctx = activity ?: return
        val input = EditTextBoldCursor(ctx).apply {
            hint = "@username"
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            isSingleLine = true
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20f)
            setPadding(p, AndroidUtilities.dp(10f), p, AndroidUtilities.dp(4f))
            addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        AlertDialog.Builder(ctx)
            .setTitle("Проверка @username (.snipeuser)")
            .setView(box)
            .setPositiveButton("Проверить") { _, _ ->
                val u = input.text?.toString()?.trim().orEmpty()
                if (u.isNotBlank()) runSnipeUserCheck(ctx, u)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runSnipeUserCheck(activity: Activity?, rawUsername: String) {
        val clean = rawUsername.trim().removePrefix("@").removePrefix("https://t.me/").removePrefix("t.me/")
            .replace(Regex("[^A-Za-z0-9_]"), "")
        if (clean.length < 4) {
            toast("MediaTok: некорректный username")
            return
        }
        toast("MediaTok: проверяю @$clean...")
        Thread {
            try {
                val html = httpGet("https://t.me/$clean")
                val hasProfileTitle = html.contains("tgme_page_title")
                val hasExtra = html.contains("tgme_page_extra")
                val status = if (hasProfileTitle && hasExtra) {
                    "❌ @$clean ЗАНЯТ (найден активный аккаунт/канал/бот)."
                } else {
                    "✅ @$clean НЕ занят активным профилем (свободен или доступен на Fragment)."
                }
                AndroidUtilities.runOnUIThread {
                    val ctx = activity ?: return@runOnUIThread
                    AlertDialog.Builder(ctx)
                        .setTitle("SnipeUser: @$clean")
                        .setMessage(status)
                        .setPositiveButton("Открыть Fragment") { _, _ ->
                            openUrl(ctx, "https://fragment.com/username/$clean")
                        }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            } catch (e: Throwable) {
                toast("Ошибка проверки @$clean: ${e.message ?: "сеть недоступна"}")
            }
        }.start()
    }

    private fun handleVipFriendsCommand(activity: Activity?, account: Int, dialogId: Long, arg: String) {
        val clean = arg.trim().lowercase(Locale.ROOT)
        if (clean == "list") {
            val mc = MessagesController.getInstance(account)
            val lines = if (vipFriends.isEmpty()) {
                "Список VIP Friends пуст.\nVIP контакты обходят .novoice и .captcha."
            } else {
                vipFriends.joinToString("\n") { id ->
                    val u = mc.getUser(id)
                    val name = if (u != null) listOfNotNull(u.first_name, u.last_name).joinToString(" ").trim() else "ID $id"
                    "• $name ($id)"
                }
            }
            showInfoDialog(activity, "VIP Friends (#1481)", lines)
            return
        }
        if (clean == "add") {
            vipFriends.add(dialogId)
            saveLongSet(KEY_VIP_FRIENDS, vipFriends)
            toast("Чат добавлен в VIP Friends")
            return
        }
        if (clean == "del" || clean == "rm") {
            vipFriends.remove(dialogId)
            saveLongSet(KEY_VIP_FRIENDS, vipFriends)
            toast("Чат удалён из VIP Friends")
            return
        }
        if (vipFriends.contains(dialogId)) {
            vipFriends.remove(dialogId)
            toast("Убрано из VIP Friends")
        } else {
            vipFriends.add(dialogId)
            toast("Добавлено в VIP Friends (обходит NoVoice и Captcha)")
        }
        saveLongSet(KEY_VIP_FRIENDS, vipFriends)
    }

    private fun openNukeConfirmDialog(activity: Activity?, account: Int, dialogId: Long) {
        val ctx = activity ?: return
        AlertDialog.Builder(ctx)
            .setTitle("⚠️ Экстренный сброс MediaTok (.nukeall)")
            .setMessage("Очистить все локальные данные MediaTok (заметки, макросы, VIP, интеграции, правила авто-удаления) и удалить ваши сообщения в текущем чате?")
            .setPositiveButton("Сбросить всё") { _, _ ->
                frozenChats.clear()
                mirrorChats.clear()
                vipFriends.clear()
                captchaVerified.clear()
                macros.clear()
                pendingCaptchaAnswer.clear()
                pendingGhost1m.clear()
                prefs().edit().clear().apply()
                MediaTokHelper.runPurgeMeFromExternal(account, dialogId, "500")
                toast("MediaTok: все локальные настройки сброшены, чищу чат...")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // MARK: - Helpers

    private fun extractPromoCode(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        val hasKeyword = lower.contains("promo") || lower.contains("промо") ||
            lower.contains("ваучер") || lower.contains("купон") ||
            lower.contains("код:") || lower.contains("code:") || lower.contains("bonus")
        if (!hasKeyword) return null
        val match = Regex("\\b([A-Z0-9][A-Z0-9\\-]{5,19})\\b").findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { candidate ->
                candidate.any { it.isDigit() } && candidate.any { it.isLetter() }
            }
        return match
    }

    private fun expandSpinSyntax(template: String): String {
        val regex = Regex("\\{([^{}]+)\\}")
        var current = template
        var guard = 0
        while (regex.containsMatchIn(current) && guard < 8) {
            current = regex.replace(current) { m ->
                val options = m.groupValues[1].split("|").filter { it.isNotBlank() }
                if (options.isEmpty()) "" else options[random.nextInt(options.size)]
            }
            guard++
        }
        return current.trim()
    }

    private fun sendText(account: Int, dialogId: Long, text: String, notify: Boolean) {
        try {
            val p = SendMessagesHelper.SendMessageParams.of(text, dialogId)
            p.notify = notify
            SendMessagesHelper.getInstance(account).sendMessage(p)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun sendReply(account: Int, dialogId: Long, text: String, replyTo: MessageObject, notify: Boolean) {
        try {
            val p = SendMessagesHelper.SendMessageParams.of(text, dialogId)
            p.replyToMsg = replyTo
            p.notify = notify
            SendMessagesHelper.getInstance(account).sendMessage(p)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun httpGet(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("User-Agent", "MediaTok-Android/1.0")
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn?.disconnect()
        }
    }

    private fun openUrl(activity: Activity?, url: String) {
        AndroidUtilities.runOnUIThread {
            try {
                val ctx = activity ?: ApplicationLoader.applicationContext
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (e: Throwable) {
                toast("Не удалось открыть ссылку: ${e.message}")
            }
        }
    }

    private fun showInfoDialog(activity: Activity?, title: String, message: String) {
        AndroidUtilities.runOnUIThread {
            val ctx = activity
            if (ctx == null) {
                toast("$title: $message")
                return@runOnUIThread
            }
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setNeutralButton("Копировать") { _, _ ->
                    copyText(title, message)
                    toast("Скопировано")
                }
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun copyText(label: String, text: String) {
        try {
            val cm = ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Throwable) {
        }
    }

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            Toast.makeText(ApplicationLoader.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
