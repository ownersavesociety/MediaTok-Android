package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.SendMessagesHelper
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object MediaTokPowerHelper {

    private const val PREFS_NAME = "mediatok_prefs"
    private const val KEY_LIQUID_BAR = "mt_ios27_liquid_bar"

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // MARK: - iOS 27 Liquid Glass Iridescent Prismatic Top Bar

    @JvmStatic
    fun isLiquidGlassBarEnabled(): Boolean = prefs().getBoolean(KEY_LIQUID_BAR, true)

    @JvmStatic
    fun setLiquidGlassBarEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_LIQUID_BAR, enabled).apply()
    }

    private class LiquidGlassBarDrawable(private val baseColor: Int) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastW = 0
        private var lastH = 0

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val isDark = AndroidUtilities.computePerceivedBrightness(baseColor) < 0.5f

            if (b.width() != lastW || b.height() != lastH) {
                lastW = b.width()
                lastH = b.height()
                val colors = if (isDark) {
                    intArrayOf(
                        0xFF141826.toInt(),
                        0xFF1C2340.toInt(),
                        0xFF281F44.toInt(),
                        0xFF1B2B3F.toInt(),
                        0xFF141826.toInt()
                    )
                } else {
                    intArrayOf(
                        0xFFF0F4FF.toInt(),
                        0xFFE6EEFF.toInt(),
                        0xFFF4E8FF.toInt(),
                        0xFFE4F6FF.toInt(),
                        0xFFF0F4FF.toInt()
                    )
                }
                fillPaint.shader = LinearGradient(
                    0f, 0f, b.width().toFloat(), b.height().toFloat(),
                    colors, floatArrayOf(0f, 0.28f, 0.54f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
                val sheenColors = if (isDark) {
                    intArrayOf(0x2A7CE7FF, 0x22C084FC, 0x20FF87C3, 0x2664F4E6)
                } else {
                    intArrayOf(0x3050C8FF, 0x26A855F7, 0x24FF66B2, 0x2A22D3EE)
                }
                glassPaint.shader = LinearGradient(
                    0f, 0f, b.width().toFloat(), 0f,
                    sheenColors, null, Shader.TileMode.CLAMP
                )
                linePaint.color = if (isDark) 0x33FFFFFF else 0x26000000
                linePaint.strokeWidth = AndroidUtilities.dpf2(0.8f)
            }

            canvas.drawRect(b, fillPaint)
            canvas.drawRect(b, glassPaint)
            val y = b.bottom - AndroidUtilities.dpf2(0.4f)
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, linePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            glassPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    @JvmStatic
    fun createActionBarBackgroundIfEnabled(baseColor: Int): Drawable? {
        if (!isLiquidGlassBarEnabled()) return null
        if (baseColor == 0 || (baseColor ushr 24) < 200) return null
        return LiquidGlassBarDrawable(baseColor)
    }

    // MARK: - Command Dispatch (.integrations, .ios27bar, .tonpay, .jbcheck/.rootcheck, .bincheck, .songlink, .spin, .prostore)

    @JvmStatic
    fun handleCommand(
        activity: Activity?,
        account: Int,
        dialogId: Long,
        cmdLower: String,
        arg: String,
        params: SendMessagesHelper.SendMessageParams?
    ): Boolean {
        return when (cmdLower) {
            ".integrations", ".steam", ".spotify", ".connections" -> {
                AndroidUtilities.runOnUIThread {
                    MediaTokIntegrationsHelper.openIntegrationsDialog(activity, account, dialogId)
                }
                true
            }
            ".ios27bar", ".liquidbar" -> {
                val next = !isLiquidGlassBarEnabled()
                setLiquidGlassBarEnabled(next)
                toast(if (next) "iOS 27 Liquid Glass верхний бар ВКЛ (обнови экран)" else "iOS 27 Liquid Glass верхний бар ВЫКЛ")
                true
            }
            ".tonpay", ".tonkeeper", ".xrocket" -> {
                AndroidUtilities.runOnUIThread { openTonPayDialog(activity, arg) }
                true
            }
            ".jbcheck", ".rootcheck", ".antijb" -> {
                AndroidUtilities.runOnUIThread { showAntiRootReport(activity) }
                true
            }
            ".bincheck" -> {
                val target = arg.ifBlank { readClipboard() }
                AndroidUtilities.runOnUIThread { showBinCheckDialog(activity, target) }
                true
            }
            ".songlink" -> {
                val target = arg.ifBlank { readClipboard() }
                resolveSonglink(activity, account, dialogId, target)
                true
            }
            ".spin" -> {
                if (arg.isBlank()) {
                    toast("Формат: .spin {Привет|Здарова} {друг|бро}!")
                } else {
                    val expanded = expandSpinSyntax(arg)
                    val p = SendMessagesHelper.SendMessageParams.of(expanded, dialogId)
                    p.replyToMsg = params?.replyToMsg
                    p.replyToTopMsg = params?.replyToTopMsg
                    SendMessagesHelper.getInstance(account).sendMessage(p)
                }
                true
            }
            ".prostore", ".tribute" -> {
                openUrl(activity, "https://t.me/tribute")
                true
            }
            else -> false
        }
    }

    // MARK: - Tonkeeper + xRocket Transfer by Address with Comment/Memo

    @JvmStatic
    fun openTonPayDialog(activity: Activity?, prefillAddress: String = "") {
        if (activity == null || activity.isFinishing) return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(10f), AndroidUtilities.dp(20f), AndroidUtilities.dp(4f))
        }
        val addrEt = EditTextBoldCursor(activity).apply {
            hint = "Адрес TON (EQ... / UQ... / name.ton)"
            setText(prefillAddress)
            setSingleLine(true)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        }
        val amountEt = EditTextBoldCursor(activity).apply {
            hint = "Сумма в TON (напр. 1.5)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        }
        val memoEt = EditTextBoldCursor(activity).apply {
            hint = "Комментарий / Memo (необязательно)"
            setSingleLine(true)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        }
        box.addView(addrEt, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f))
        box.addView(amountEt, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f))
        box.addView(memoEt, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        AlertDialog.Builder(activity)
            .setTitle("Перевод TON (Tonkeeper / xRocket)")
            .setView(box)
            .setPositiveButton("Tonkeeper") { _, _ ->
                val addr = addrEt.text?.toString()?.trim().orEmpty()
                val amtStr = amountEt.text?.toString()?.trim()?.replace(',', '.').orEmpty()
                val memo = memoEt.text?.toString()?.trim().orEmpty()
                if (addr.isEmpty()) {
                    toast("Укажите TON-адрес получателя")
                    return@setPositiveButton
                }
                val nano = try {
                    BigDecimal(amtStr).multiply(BigDecimal("1000000000")).toBigInteger().toString()
                } catch (_: Throwable) {
                    "0"
                }
                val q = StringBuilder()
                if (nano != "0") q.append("amount=").append(nano)
                if (memo.isNotEmpty()) {
                    if (q.isNotEmpty()) q.append('&')
                    q.append("text=").append(URLEncoder.encode(memo, "UTF-8"))
                }
                val queryPart = if (q.isNotEmpty()) "?$q" else ""
                val deepUri = "ton://transfer/$addr$queryPart"
                val webUri = "https://app.tonkeeper.com/transfer/$addr$queryPart"
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepUri)))
                } catch (_: Throwable) {
                    openUrl(activity, webUri)
                }
            }
            .setNeutralButton("xRocket") { _, _ ->
                val addr = addrEt.text?.toString()?.trim().orEmpty()
                val amtStr = amountEt.text?.toString()?.trim().orEmpty()
                val memo = memoEt.text?.toString()?.trim().orEmpty()
                val summary = listOfNotNull(
                    addr.takeIf { it.isNotEmpty() },
                    amtStr.takeIf { it.isNotEmpty() }?.let { "$it TON" },
                    memo.takeIf { it.isNotEmpty() }?.let { "Комментарий: $it" }
                ).joinToString(" | ")
                if (summary.isNotEmpty()) copyText(summary)
                toast("Реквизиты скопированы — открываю @xrocket")
                openUrl(activity, "https://t.me/xrocket")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // MARK: - Multi-Layer Anti-Root & Anti-Hook Shield

    @JvmStatic
    fun performRootScan(): Pair<Boolean, List<String>> {
        val reasons = ArrayList<String>()
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/local/su", "/data/local/bin/su", "/sbin/.magisk",
            "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap"
        )
        for (p in paths) {
            if (File(p).exists()) {
                reasons.add("FS:${File(p).name}")
                break
            }
        }
        val tags = Build.TAGS.orEmpty()
        if (tags.contains("test-keys")) {
            reasons.add("Build:test-keys")
        }
        try {
            val maps = File("/proc/self/maps").readText().lowercase(Locale.ROOT)
            val hooks = arrayOf("frida", "xposed", "lsposed", "edxposed", "substrate", "zygisk")
            for (h in hooks) {
                if (maps.contains(h)) {
                    reasons.add("Hook:$h")
                    break
                }
            }
        } catch (_: Throwable) {
        }
        return Pair(reasons.isNotEmpty(), reasons)
    }

    private fun showAntiRootReport(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val (compromised, reasons) = performRootScan()
        val msg = if (compromised) {
            "Обнаружены признаки Root / Hook:\n• ${reasons.joinToString("\n• ")}"
        } else {
            "Окружение чистое: Root, Magisk, KernelSU, Frida и LSPosed не обнаружены."
        }
        AlertDialog.Builder(activity)
            .setTitle("Anti-Root & Anti-Hook Shield")
            .setMessage(msg)
            .setPositiveButton("ОК", null)
            .show()
    }

    // MARK: - Luhn & BIN Card Validator (.bincheck)

    private fun showBinCheckDialog(activity: Activity?, raw: String) {
        if (activity == null || activity.isFinishing) return
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 6) {
            toast("Укажите минимум 6 цифр карты (.bincheck <номер>)")
            return
        }
        var sum = 0
        var alt = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        val luhnOk = digits.length in 13..19 && (sum % 10 == 0)
        val network = when {
            digits.startsWith("220") -> "МИР"
            digits.startsWith("4") -> "Visa"
            digits.startsWith("5") || digits.startsWith("2") -> "Mastercard"
            digits.startsWith("34") || digits.startsWith("37") -> "American Express"
            digits.startsWith("62") -> "UnionPay"
            else -> "Неизвестная сеть"
        }
        AlertDialog.Builder(activity)
            .setTitle("Проверка BIN / Luhn")
            .setMessage("BIN: ${digits.take(6)}\nСеть: $network\nКонтрольная сумма Luhn: ${if (luhnOk) "Валидна ✓" else "Не пройдена"}")
            .setPositiveButton("ОК", null)
            .show()
    }

    // MARK: - Songlink Universal Music Resolver (.songlink)

    private fun resolveSonglink(activity: Activity?, account: Int, dialogId: Long, rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http")) {
            toast("Укажите ссылку на трек (.songlink <url>)")
            return
        }
        toast("Ищу трек на всех площадках...")
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val api = "https://api.song.link/v1-alpha.1/links?url=${URLEncoder.encode(url, "UTF-8")}"
                conn = (URL(api).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "MediaTok/Songlink")
                }
                val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val pageUrl = obj.optString("pageUrl", "")
                val links = obj.optJSONObject("linksByPlatform")
                val lines = ArrayList<String>()
                if (pageUrl.isNotEmpty()) lines.add("Songlink: $pageUrl")
                listOf("spotify" to "Spotify", "appleMusic" to "Apple Music", "youtubeMusic" to "YouTube Music", "yandex" to "Яндекс Музыка").forEach { (k, label) ->
                    val u = links?.optJSONObject(k)?.optString("url", "").orEmpty()
                    if (u.isNotEmpty()) lines.add("$label: $u")
                }
                val out = lines.joinToString("\n")
                if (out.isNotEmpty()) {
                    copyText(out)
                    AndroidUtilities.runOnUIThread {
                        if (activity != null && !activity.isFinishing) {
                            AlertDialog.Builder(activity)
                                .setTitle("Ссылки на трек (скопировано)")
                                .setMessage(out)
                                .setPositiveButton("Отправить в чат") { _, _ ->
                                    SendMessagesHelper.getInstance(account).sendMessage(
                                        SendMessagesHelper.SendMessageParams.of(out, dialogId)
                                    )
                                }
                                .setNegativeButton("Закрыть", null)
                                .show()
                        }
                    }
                } else {
                    toast("Трек не найден в Songlink")
                }
            } catch (e: Throwable) {
                FileLog.e(e)
                toast("Ошибка Songlink")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    @JvmStatic
    fun expandSpinSyntax(template: String): String {
        val regex = Regex("\\{([^{}]+)\\}")
        var res = template
        var guard = 0
        while (regex.containsMatchIn(res) && guard < 12) {
            res = regex.replace(res) { m ->
                val opts = m.groupValues[1].split('|')
                opts.randomOrNull().orEmpty()
            }
            guard++
        }
        return res
    }

    private fun readClipboard(): String {
        val cm = ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return cm?.primaryClip?.getItemAt(0)?.coerceToText(ApplicationLoader.applicationContext)?.toString().orEmpty()
    }

    private fun copyText(text: String) {
        val cm = ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("MediaTok", text))
    }

    private fun openUrl(activity: Activity?, url: String) {
        try {
            val ctx = activity ?: ApplicationLoader.applicationContext
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (activity == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            Toast.makeText(ApplicationLoader.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
