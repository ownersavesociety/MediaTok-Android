package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

data class VisualPeerOverride(
    var name: String = "",
    var username: String = "",
    var alsoUsernames: String = "",
    var bio: String = "",
    var phone: String = "",
    var badgeMode: Int = MediaTokVisualHelper.BADGE_DEFAULT,
    var avatarPath: String = ""
) {
    fun isEmpty(): Boolean {
        return name.isEmpty() &&
            username.isEmpty() &&
            alsoUsernames.isEmpty() &&
            bio.isEmpty() &&
            phone.isEmpty() &&
            badgeMode == MediaTokVisualHelper.BADGE_DEFAULT &&
            avatarPath.isEmpty()
    }
}

object MediaTokVisualHelper {

    private const val PREFS_NAME = "mediatok_prefs"
    private const val PREFIX_VIS = "vis_profile_"
    private const val MAX_AVATAR_4K_PX = 3840
    const val REQUEST_CODE_VISUAL_AVATAR = 9482

    const val BADGE_DEFAULT = 0
    const val BADGE_VERIFIED = 1
    const val BADGE_PREMIUM = 2
    const val BADGE_SCAM = 3
    const val BADGE_FAKE = 4
    const val BADGE_HIDE = 5

    private val BADGE_LABELS = arrayOf(
        "По умолчанию (как в Telegram)",
        "✓ Верификация (Галочка)",
        "★ Telegram Premium",
        "⚠ SCAM",
        "⚠ FAKE",
        "Скрыть все бейджи"
    )

    private const val FAKE_VOLUME_ID = -900900900900L

    private val cache = ConcurrentHashMap<Long, VisualPeerOverride>()

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var pendingAvatarDialogId: Long = 0L

    @Volatile
    private var pendingAccount: Int = UserConfig.selectedAccount

    private fun prefs(): SharedPreferences {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureLoaded() {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded) return
            try {
                val all = prefs().all
                for ((k, v) in all) {
                    if (!k.startsWith(PREFIX_VIS)) continue
                    val did = k.removePrefix(PREFIX_VIS).toLongOrNull() ?: continue
                    val jsonStr = v as? String ?: continue
                    val parsed = decodeJson(jsonStr)
                    if (parsed != null && !parsed.isEmpty()) {
                        cache[did] = parsed
                    }
                }
            } catch (e: Throwable) {
                FileLog.e(e)
            }
            cacheLoaded = true
        }
    }

    private fun decodeJson(raw: String): VisualPeerOverride? {
        return try {
            val obj = JSONObject(raw)
            VisualPeerOverride(
                name = obj.optString("name", ""),
                username = obj.optString("username", ""),
                alsoUsernames = obj.optString("also", ""),
                bio = obj.optString("bio", ""),
                phone = obj.optString("phone", ""),
                badgeMode = obj.optInt("badge", BADGE_DEFAULT).coerceIn(BADGE_DEFAULT, BADGE_HIDE),
                avatarPath = obj.optString("avatar", "")
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveOverride(dialogId: Long, ov: VisualPeerOverride) {
        ensureLoaded()
        val key = PREFIX_VIS + dialogId
        if (ov.isEmpty()) {
            cache.remove(dialogId)
            prefs().edit().remove(key).apply()
            return
        }
        cache[dialogId] = ov
        val obj = JSONObject().apply {
            if (ov.name.isNotEmpty()) put("name", ov.name)
            if (ov.username.isNotEmpty()) put("username", ov.username)
            if (ov.alsoUsernames.isNotEmpty()) put("also", ov.alsoUsernames)
            if (ov.bio.isNotEmpty()) put("bio", ov.bio)
            if (ov.phone.isNotEmpty()) put("phone", ov.phone)
            if (ov.badgeMode != BADGE_DEFAULT) put("badge", ov.badgeMode)
            if (ov.avatarPath.isNotEmpty()) put("avatar", ov.avatarPath)
        }
        prefs().edit().putString(key, obj.toString()).apply()
    }

    @JvmStatic
    fun getOverride(dialogId: Long): VisualPeerOverride? {
        if (dialogId == 0L) return null
        ensureLoaded()
        return cache[dialogId]
    }

    @JvmStatic
    fun getVisualName(dialogId: Long): String? {
        val v = getOverride(dialogId)?.name
        return if (!v.isNullOrBlank()) v else null
    }

    @JvmStatic
    fun resolvePeerName(dialogId: Long, fallback: String?): String? {
        return getVisualName(dialogId) ?: fallback
    }

    @JvmStatic
    fun getVisualBio(dialogId: Long): String? {
        val v = getOverride(dialogId)?.bio
        return if (!v.isNullOrBlank()) v else null
    }

    @JvmStatic
    fun getVisualPhone(dialogId: Long): String? {
        val v = getOverride(dialogId)?.phone
        return if (!v.isNullOrBlank()) v else null
    }

    @JvmStatic
    fun getVisualBadgeMode(dialogId: Long): Int {
        return getOverride(dialogId)?.badgeMode ?: BADGE_DEFAULT
    }

    @JvmStatic
    fun resolveVerified(dialogId: Long, real: Boolean): Boolean {
        return when (getVisualBadgeMode(dialogId)) {
            BADGE_VERIFIED -> true
            BADGE_SCAM, BADGE_FAKE, BADGE_HIDE -> false
            else -> real
        }
    }

    @JvmStatic
    fun resolvePremium(dialogId: Long, real: Boolean): Boolean {
        return when (getVisualBadgeMode(dialogId)) {
            BADGE_PREMIUM -> true
            BADGE_HIDE -> false
            else -> real
        }
    }

    @JvmStatic
    fun resolveScam(dialogId: Long, real: Boolean): Boolean {
        return when (getVisualBadgeMode(dialogId)) {
            BADGE_SCAM -> true
            BADGE_VERIFIED, BADGE_FAKE, BADGE_HIDE -> false
            else -> real
        }
    }

    @JvmStatic
    fun resolveFake(dialogId: Long, real: Boolean): Boolean {
        return when (getVisualBadgeMode(dialogId)) {
            BADGE_FAKE -> true
            BADGE_VERIFIED, BADGE_SCAM, BADGE_HIDE -> false
            else -> real
        }
    }

    @JvmStatic
    fun shouldHideEmojiStatus(dialogId: Long): Boolean {
        return getVisualBadgeMode(dialogId) == BADGE_HIDE
    }

    @JvmStatic
    fun normalizeUsername(raw: String?): String {
        if (raw == null) return ""
        var s = raw.trim()
        val prefixes = arrayOf("https://t.me/", "http://t.me/", "t.me/")
        for (p in prefixes) {
            if (s.lowercase(Locale.ROOT).startsWith(p)) {
                s = s.substring(p.length)
                break
            }
        }
        while (s.startsWith("@") || s.startsWith("/")) {
            s = s.substring(1)
        }
        while (s.endsWith("/")) {
            s = s.dropLast(1)
        }
        return s.trim()
    }

    @JvmStatic
    fun splitUsernames(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        val tokens = raw.split(Regex("[\\s,;\\n\\r]+"))
        for (tok in tokens) {
            val clean = normalizeUsername(tok)
            if (clean.isEmpty()) continue
            val lower = clean.lowercase(Locale.ROOT)
            if (seen.add(lower)) {
                result.add(clean)
            }
        }
        return result
    }

    @JvmStatic
    fun hasUsernameOverride(dialogId: Long): Boolean {
        val ov = getOverride(dialogId) ?: return false
        return ov.username.isNotBlank() || ov.alsoUsernames.isNotBlank()
    }

    @JvmStatic
    fun hasAnyUsername(dialogId: Long): Boolean {
        val ov = getOverride(dialogId) ?: return false
        return splitUsernames(ov.username).isNotEmpty() || splitUsernames(ov.alsoUsernames).isNotEmpty()
    }

    @JvmStatic
    fun resolveMainUsername(dialogId: Long, realMain: String?): String? {
        val ov = getOverride(dialogId) ?: return realMain
        val mainTokens = splitUsernames(ov.username)
        if (mainTokens.isNotEmpty()) return mainTokens[0]
        if (!realMain.isNullOrBlank()) return realMain
        val alsoTokens = splitUsernames(ov.alsoUsernames)
        if (alsoTokens.isNotEmpty()) return alsoTokens[0]
        return realMain
    }

    @JvmStatic
    fun resolveUsernames(
        dialogId: Long,
        realUsernames: ArrayList<TLRPC.TL_username>?,
        resolvedMain: String?
    ): ArrayList<TLRPC.TL_username> {
        val out = ArrayList<TLRPC.TL_username>()
        val seen = HashSet<String>()
        val mainLower = resolvedMain?.lowercase(Locale.ROOT)
        val ov = getOverride(dialogId)

        if (realUsernames != null) {
            for (u in realUsernames) {
                if (u == null || TextUtils.isEmpty(u.username)) continue
                val low = u.username.lowercase(Locale.ROOT)
                if (seen.add(low)) {
                    out.add(u)
                }
            }
        }

        if (ov != null) {
            val visualList = ArrayList<String>()
            val mainTokens = splitUsernames(ov.username)
            if (mainTokens.size > 1) {
                for (i in 1 until mainTokens.size) {
                    visualList.add(mainTokens[i])
                }
            }
            visualList.addAll(splitUsernames(ov.alsoUsernames))

            if (!resolvedMain.isNullOrBlank() && seen.add(mainLower!!)) {
                out.add(0, TLRPC.TL_username().apply {
                    username = resolvedMain
                    active = true
                    editable = true
                })
            }

            for (v in visualList) {
                val low = v.lowercase(Locale.ROOT)
                if (seen.add(low)) {
                    out.add(TLRPC.TL_username().apply {
                        username = v
                        active = true
                        editable = true
                    })
                }
            }
        }
        return out
    }

    @JvmStatic
    fun isVisualOnlyUsername(dialogId: Long, username: String?): Boolean {
        if (username.isNullOrBlank()) return false
        val ov = getOverride(dialogId) ?: return false
        val target = normalizeUsername(username).lowercase(Locale.ROOT)
        for (t in splitUsernames(ov.username)) {
            if (t.lowercase(Locale.ROOT) == target) return true
        }
        for (t in splitUsernames(ov.alsoUsernames)) {
            if (t.lowercase(Locale.ROOT) == target) return true
        }
        return false
    }

    @JvmStatic
    fun getVisualAvatarPath(dialogId: Long): String? {
        val p = getOverride(dialogId)?.avatarPath
        if (p.isNullOrBlank()) return null
        return try {
            val f = File(p)
            if (f.exists() && f.isFile && f.length() > 0L) p else null
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun getVisualAvatarImageLocation(dialogId: Long): ImageLocation? {
        val p = getVisualAvatarPath(dialogId) ?: return null
        return ImageLocation.getForPath(p)
    }

    @JvmStatic
    fun getVisualAvatarFakeLocation(dialogId: Long): TLRPC.FileLocation? {
        if (getVisualAvatarPath(dialogId) == null) return null
        return TLRPC.TL_fileLocationToBeDeprecated().apply {
            volume_id = FAKE_VOLUME_ID
            local_id = (dialogId xor (dialogId ushr 32)).toInt()
            dc_id = 1
        }
    }

    @JvmStatic
    fun isVisualAvatarFakeLocation(dialogId: Long, loc: TLRPC.FileLocation?): Boolean {
        if (loc == null) return false
        if (getVisualAvatarPath(dialogId) == null) return false
        return loc.volume_id == FAKE_VOLUME_ID && loc.local_id == (dialogId xor (dialogId ushr 32)).toInt()
    }

    private fun avatarStorageDir(): File {
        val dir = File(ApplicationLoader.applicationContext.filesDir, "mediatok_avatars")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun deleteOldAvatarFiles(dialogId: Long) {
        try {
            val dir = avatarStorageDir()
            val prefix = "avatar_${dialogId}_"
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix)) {
                    f.delete()
                }
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun importAvatarFromUri(uri: Uri, dialogId: Long, account: Int, fragment: BaseFragment?) {
        Thread {
            try {
                val cr = ApplicationLoader.applicationContext.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                val rawW = bounds.outWidth
                val rawH = bounds.outHeight
                if (rawW <= 0 || rawH <= 0) {
                    toast("MediaTok: не удалось прочитать изображение")
                    return@Thread
                }

                var sample = 1
                var maxDim = max(rawW, rawH)
                while (maxDim / sample > MAX_AVATAR_4K_PX * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bmp = cr.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                if (bmp == null) {
                    toast("MediaTok: ошибка декодирования изображения")
                    return@Thread
                }

                var exifRotation = 0
                try {
                    cr.openInputStream(uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        exifRotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    }
                } catch (_: Throwable) {
                }

                if (exifRotation != 0) {
                    val m = Matrix().apply { postRotate(exifRotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (rotated !== bmp) {
                        bmp.recycle()
                        bmp = rotated
                    }
                }

                val curMax = max(bmp.width, bmp.height)
                if (curMax > MAX_AVATAR_4K_PX) {
                    val ratio = MAX_AVATAR_4K_PX.toFloat() / curMax.toFloat()
                    val dstW = (bmp.width * ratio).roundToInt().coerceAtLeast(1)
                    val dstH = (bmp.height * ratio).roundToInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
                    if (scaled !== bmp) {
                        bmp.recycle()
                        bmp = scaled
                    }
                }

                val outW = bmp.width
                val outH = bmp.height
                deleteOldAvatarFiles(dialogId)
                val outFile = File(avatarStorageDir(), "avatar_${dialogId}_${System.currentTimeMillis()}.jpg")
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    fos.flush()
                }
                bmp.recycle()

                val absPath = outFile.absolutePath
                AndroidUtilities.runOnUIThread {
                    val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                    cur.avatarPath = absPath
                    saveOverride(dialogId, cur)
                    notifyPeerChanged(account, dialogId, fragment)
                    toast("MediaTok: установлен 4K аватар (${outW}×${outH})")
                }
            } catch (e: Throwable) {
                FileLog.e(e)
                toast("MediaTok: не удалось сохранить аватар (${e.message})")
            }
        }.start()
    }

    @JvmStatic
    fun pickVisualAvatar(fragment: BaseFragment?, dialogId: Long) {
        if (fragment == null || dialogId == 0L) return
        pendingAvatarDialogId = dialogId
        pendingAccount = fragment.currentAccount
        try {
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            fragment.startActivityForResult(Intent.createChooser(pickIntent, "Выберите 4K фото для аватара"), REQUEST_CODE_VISUAL_AVATAR)
        } catch (e: Throwable) {
            FileLog.e(e)
            toast("MediaTok: не удалось открыть галерею")
        }
    }

    @JvmStatic
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?, account: Int, fragment: BaseFragment?): Boolean {
        if (requestCode != REQUEST_CODE_VISUAL_AVATAR) return false
        val dialogId = pendingAvatarDialogId
        val targetAcc = if (pendingAccount >= 0) pendingAccount else account
        pendingAvatarDialogId = 0L
        if (resultCode != Activity.RESULT_OK || data?.data == null || dialogId == 0L) {
            return true
        }
        importAvatarFromUri(data.data!!, dialogId, targetAcc, fragment)
        return true
    }

    @JvmStatic
    fun notifyPeerChanged(account: Int, dialogId: Long, fragment: BaseFragment?) {
        AndroidUtilities.runOnUIThread {
            try {
                val mask = MessagesController.UPDATE_MASK_NAME or
                    MessagesController.UPDATE_MASK_AVATAR or
                    MessagesController.UPDATE_MASK_USER_PHONE or
                    MessagesController.UPDATE_MASK_STATUS
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, mask)
                if (fragment is ProfileActivity) {
                    fragment.refreshVisualProfile()
                } else {
                    val last = LaunchActivity.getLastFragment()
                    if (last is ProfileActivity) {
                        last.refreshVisualProfile()
                    }
                }
            } catch (e: Throwable) {
                FileLog.e(e)
            }
        }
    }

    @JvmStatic
    fun showVisualProfileDialog(fragment: BaseFragment?, dialogId: Long, defaultTitle: String?) {
        val ctx = fragment?.parentActivity ?: LaunchActivity.getLastFragment()?.parentActivity ?: return
        if (dialogId == 0L) return
        val account = fragment?.currentAccount ?: UserConfig.selectedAccount
        val current = getOverride(dialogId)?.copy() ?: VisualPeerOverride()

        val scroll = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(20f)
            setPadding(p, AndroidUtilities.dp(8f), p, AndroidUtilities.dp(8f))
        }
        scroll.addView(layout)

        fun addField(label: String, hintText: String, initial: String, singleLine: Boolean = true): EditTextBoldCursor {
            val tv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
                text = label
            }
            layout.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 2f))

            val et = EditTextBoldCursor(ctx).apply {
                hint = hintText
                setText(initial)
                setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                isSingleLine = singleLine
                if (!singleLine) {
                    minLines = 2
                    maxLines = 5
                    gravity = Gravity.TOP or Gravity.START
                } else {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
            }
            layout.addView(et, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 4f))
            return et
        }

        val nameInput = addField("Визуальное имя", defaultTitle ?: "Оставьте пустым для оригинала", current.name)
        val usernameInput = addField("Основной визуальный @username", "durov (без @ или с @)", current.username)
        val alsoInput = addField("Доп. юзернеймы («а также @...»)", "nick1, nick2, nick3 (через пробел или запятую)", current.alsoUsernames)
        val phoneInput = addField("Визуальный телефон", "+7 999 000-00-00", current.phone)
        val bioInput = addField("Визуальное БИО / описание", "Локальное описание профиля...", current.bio, singleLine = false)

        var selectedBadge = current.badgeMode

        val badgeBtn = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4))
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP)
            text = "Значок: ${BADGE_LABELS[selectedBadge]}"
        }
        badgeBtn.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Выберите визуальный значок")
                .setItems(BADGE_LABELS) { _, which ->
                    selectedBadge = which.coerceIn(BADGE_DEFAULT, BADGE_HIDE)
                    badgeBtn.text = "Значок: ${BADGE_LABELS[selectedBadge]}"
                }
                .show()
        }
        layout.addView(badgeBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 2f))

        val hasAvatar4k = getVisualAvatarPath(dialogId) != null
        val avatarInfo = if (hasAvatar4k) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(current.avatarPath, opts)
            if (opts.outWidth > 0) " (${opts.outWidth}×${opts.outHeight})" else " (активен)"
        } else {
            ""
        }

        var parentDialog: AlertDialog? = null

        val pickAvatarBtn = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4))
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP)
            text = "🖼 Выбрать 4K аватар (до 3840px)$avatarInfo"
        }
        pickAvatarBtn.setOnClickListener {
            // Save currently typed text fields first before launching gallery picker
            applyTextFieldsToOverride(
                account = account,
                dialogId = dialogId,
                name = nameInput.text?.toString().orEmpty(),
                usernameRaw = usernameInput.text?.toString().orEmpty(),
                alsoRaw = alsoInput.text?.toString().orEmpty(),
                phone = phoneInput.text?.toString().orEmpty(),
                bio = bioInput.text?.toString().orEmpty(),
                badgeMode = selectedBadge,
                avatarPath = current.avatarPath,
                fragment = fragment
            )
            parentDialog?.dismiss()
            if (fragment != null) {
                pickVisualAvatar(fragment, dialogId)
            } else {
                val last = LaunchActivity.getLastFragment()
                if (last != null) pickVisualAvatar(last, dialogId)
            }
        }
        layout.addView(pickAvatarBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 2f))

        if (hasAvatar4k) {
            val removeAvatarBtn = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(Theme.getColor(Theme.key_text_RedRegular))
                setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(8f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
                background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP)
                text = "✕ Удалить 4K аватар"
            }
            removeAvatarBtn.setOnClickListener {
                deleteOldAvatarFiles(dialogId)
                current.avatarPath = ""
                saveOverride(dialogId, current)
                notifyPeerChanged(account, dialogId, fragment)
                removeAvatarBtn.visibility = View.GONE
                pickAvatarBtn.text = "🖼 Выбрать 4K аватар (до 3840px)"
                toast("MediaTok: 4K аватар удалён")
            }
            layout.addView(removeAvatarBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle("MediaTok: Визуал и 4K ава")
            .setView(scroll)
            .setNeutralButton("Сбросить всё") { _, _ ->
                deleteOldAvatarFiles(dialogId)
                saveOverride(dialogId, VisualPeerOverride())
                notifyPeerChanged(account, dialogId, fragment)
                toast("MediaTok: визуал профиля сброшен")
            }
            .setPositiveButton(LocaleController.getString(R.string.Save)) { _, _ ->
                applyTextFieldsToOverride(
                    account = account,
                    dialogId = dialogId,
                    name = nameInput.text?.toString().orEmpty(),
                    usernameRaw = usernameInput.text?.toString().orEmpty(),
                    alsoRaw = alsoInput.text?.toString().orEmpty(),
                    phone = phoneInput.text?.toString().orEmpty(),
                    bio = bioInput.text?.toString().orEmpty(),
                    badgeMode = selectedBadge,
                    avatarPath = current.avatarPath,
                    fragment = fragment
                )
                toast("MediaTok: визуал профиля сохранён")
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        parentDialog = builder.create()
        parentDialog.show()
    }

    private fun applyTextFieldsToOverride(
        account: Int,
        dialogId: Long,
        name: String,
        usernameRaw: String,
        alsoRaw: String,
        phone: String,
        bio: String,
        badgeMode: Int,
        avatarPath: String,
        fragment: BaseFragment?
    ) {
        val mainList = splitUsernames(usernameRaw)
        val alsoList = ArrayList<String>()
        val mainToken = if (mainList.isNotEmpty()) mainList[0] else ""
        if (mainList.size > 1) {
            for (i in 1 until mainList.size) {
                alsoList.add(mainList[i])
            }
        }
        for (a in splitUsernames(alsoRaw)) {
            if (!a.equals(mainToken, ignoreCase = true) && alsoList.none { it.equals(a, ignoreCase = true) }) {
                alsoList.add(a)
            }
        }
        val ov = VisualPeerOverride(
            name = name.trim(),
            username = mainToken,
            alsoUsernames = alsoList.joinToString(", "),
            bio = bio.trim(),
            phone = phone.trim(),
            badgeMode = badgeMode,
            avatarPath = avatarPath
        )
        saveOverride(dialogId, ov)
        notifyPeerChanged(account, dialogId, fragment)
    }

    @JvmStatic
    fun handleCommand(account: Int, dialogId: Long, cmdLower: String, arg: String): Boolean {
        val frag = LaunchActivity.getLastFragment()
        return when (cmdLower) {
            ".vis" -> {
                AndroidUtilities.runOnUIThread {
                    showVisualProfileDialog(frag, dialogId, null)
                }
                true
            }
            ".visname" -> {
                val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                cur.name = if (isOff(arg)) "" else arg.trim()
                saveOverride(dialogId, cur)
                notifyPeerChanged(account, dialogId, frag)
                toast(if (cur.name.isEmpty()) "MediaTok: визуальное имя сброшено" else "MediaTok: имя -> ${cur.name}")
                true
            }
            ".visuser" -> {
                val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                if (isOff(arg)) {
                    cur.username = ""
                    cur.alsoUsernames = ""
                    toast("MediaTok: визуальные юзернеймы сброшены")
                } else {
                    val tokens = splitUsernames(arg)
                    cur.username = tokens.firstOrNull().orEmpty()
                    if (tokens.size > 1) {
                        cur.alsoUsernames = tokens.drop(1).joinToString(", ")
                    }
                    toast("MediaTok: @${cur.username}" + if (cur.alsoUsernames.isNotEmpty()) " (+ ${cur.alsoUsernames})" else "")
                }
                saveOverride(dialogId, cur)
                notifyPeerChanged(account, dialogId, frag)
                true
            }
            ".visalso" -> {
                val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                if (isOff(arg)) {
                    cur.alsoUsernames = ""
                    toast("MediaTok: доп. юзернеймы сброшены")
                } else {
                    val tokens = splitUsernames(arg)
                    cur.alsoUsernames = tokens.joinToString(", ")
                    toast("MediaTok: а также -> ${cur.alsoUsernames}")
                }
                saveOverride(dialogId, cur)
                notifyPeerChanged(account, dialogId, frag)
                true
            }
            ".visbio" -> {
                val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                cur.bio = if (isOff(arg)) "" else arg.trim()
                saveOverride(dialogId, cur)
                notifyPeerChanged(account, dialogId, frag)
                toast(if (cur.bio.isEmpty()) "MediaTok: визуальное БИО сброшено" else "MediaTok: БИО обновлено")
                true
            }
            ".visphone" -> {
                val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                cur.phone = if (isOff(arg)) "" else arg.trim()
                saveOverride(dialogId, cur)
                notifyPeerChanged(account, dialogId, frag)
                toast(if (cur.phone.isEmpty()) "MediaTok: телефон сброшен" else "MediaTok: телефон -> ${cur.phone}")
                true
            }
            ".visbadge" -> {
                val lower = arg.trim().lowercase(Locale.ROOT)
                val mode = when (lower) {
                    "verified", "verif", "v", "check", "галка" -> BADGE_VERIFIED
                    "premium", "prem", "p", "star", "прем" -> BADGE_PREMIUM
                    "scam", "скам" -> BADGE_SCAM
                    "fake", "фейк" -> BADGE_FAKE
                    "hide", "none", "скрыть" -> BADGE_HIDE
                    "off", "default", "reset", "сброс", "" -> BADGE_DEFAULT
                    else -> {
                        toast("MediaTok: .visbadge <verified|premium|scam|fake|hide|off>")
                        return true
                    }
                }
                val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                cur.badgeMode = mode
                saveOverride(dialogId, cur)
                notifyPeerChanged(account, dialogId, frag)
                toast("MediaTok значок: ${BADGE_LABELS[mode]}")
                true
            }
            ".visclear" -> {
                deleteOldAvatarFiles(dialogId)
                saveOverride(dialogId, VisualPeerOverride())
                notifyPeerChanged(account, dialogId, frag)
                toast("MediaTok: весь визуал этого чата сброшен")
                true
            }
            else -> false
        }
    }

    private fun isOff(arg: String): Boolean {
        val clean = arg.trim().lowercase(Locale.ROOT)
        return clean.isEmpty() || clean == "off" || clean == "clear" || clean == "reset" || clean == "выкл" || clean == "-"
    }

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            Toast.makeText(ApplicationLoader.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
