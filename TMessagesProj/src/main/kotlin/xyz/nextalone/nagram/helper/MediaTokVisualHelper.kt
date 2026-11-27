package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
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
import org.telegram.messenger.FileLoader
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class VisualPeerOverride(
    var name: String = "",
    var username: String = "",
    var alsoUsernames: String = "",
    var bio: String = "",
    var phone: String = "",
    var badgeMode: Int = MediaTokVisualHelper.BADGE_DEFAULT,
    var avatarPath: String = "",
    var avatarVideoPath: String = "",
    var remotePhotoUrl: String = "",
    var remoteVideoUrl: String = "",
    var remoteUpdatedAt: Long = 0L
) {
    fun isEmpty(): Boolean {
        return name.isEmpty() &&
            username.isEmpty() &&
            alsoUsernames.isEmpty() &&
            bio.isEmpty() &&
            phone.isEmpty() &&
            badgeMode == MediaTokVisualHelper.BADGE_DEFAULT &&
            avatarPath.isEmpty() &&
            avatarVideoPath.isEmpty() &&
            remotePhotoUrl.isEmpty() &&
            remoteVideoUrl.isEmpty() &&
            remoteUpdatedAt == 0L
    }
}

object MediaTokVisualHelper {

    private const val PREFS_NAME = "mediatok_prefs"
    private const val PREFIX_VIS = "vis_profile_"
    private const val MAX_AVATAR_4K_PX = 3840
    private const val GLOBAL_SYNC_TTL_MS = 10L * 60L * 1000L
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
    private const val FAKE_VIDEO_VOLUME_BASE = -9000001000L

    private val cache = ConcurrentHashMap<Long, VisualPeerOverride>()
    private val lastGlobalCheckMs = ConcurrentHashMap<Long, Long>()

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
                avatarPath = obj.optString("avatar", ""),
                avatarVideoPath = obj.optString("avatarVideo", ""),
                remotePhotoUrl = obj.optString("remotePhotoUrl", ""),
                remoteVideoUrl = obj.optString("remoteVideoUrl", ""),
                remoteUpdatedAt = obj.optLong("remoteUpdatedAt", 0L)
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
            if (ov.avatarVideoPath.isNotEmpty()) put("avatarVideo", ov.avatarVideoPath)
            if (ov.remotePhotoUrl.isNotEmpty()) put("remotePhotoUrl", ov.remotePhotoUrl)
            if (ov.remoteVideoUrl.isNotEmpty()) put("remoteVideoUrl", ov.remoteVideoUrl)
            if (ov.remoteUpdatedAt != 0L) put("remoteUpdatedAt", ov.remoteUpdatedAt)
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
    fun getVisualAvatarVideoPath(dialogId: Long): String? {
        val p = getOverride(dialogId)?.avatarVideoPath
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
    fun getVisualAvatarFakeLocation(dialogId: Long): TLRPC.TL_fileLocationToBeDeprecated? {
        if (getVisualAvatarPath(dialogId) == null) return null
        return TLRPC.TL_fileLocationToBeDeprecated().apply {
            volume_id = FAKE_VOLUME_ID
            local_id = (dialogId xor (dialogId ushr 32)).toInt()
            dc_id = 1
        }
    }

    @JvmStatic
    fun getVisualAvatarVideoFakeLocation(dialogId: Long): TLRPC.TL_fileLocationToBeDeprecated? {
        val visVideo = getVisualAvatarVideoPath(dialogId) ?: return null
        val srcFile = File(visVideo)
        val volumeId = FAKE_VIDEO_VOLUME_BASE - abs(dialogId % 1000000000L)
        val localId = ((srcFile.lastModified() / 1000L) xor dialogId).toInt()
        try {
            val cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE)
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val cacheFile = File(cacheDir, "${volumeId}_${localId}.mp4")
            if (!cacheFile.exists() || cacheFile.length() != srcFile.length()) {
                srcFile.copyTo(cacheFile, overwrite = true)
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        return TLRPC.TL_fileLocationToBeDeprecated().apply {
            volume_id = volumeId
            local_id = localId
            dc_id = 1
        }
    }

    @JvmStatic
    fun getVisualAvatarVideoImageLocation(dialogId: Long): ImageLocation? {
        val visVideo = getVisualAvatarVideoPath(dialogId) ?: return null
        val fakeLoc = getVisualAvatarVideoFakeLocation(dialogId) ?: return null
        return ImageLocation.getForPath(visVideo)?.apply {
            location = fakeLoc
            imageType = FileLoader.IMAGE_TYPE_ANIMATION
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

    private fun deleteOldAvatarFiles(dialogId: Long, keepPhotoName: String? = null, keepVideoName: String? = null) {
        try {
            val dir = avatarStorageDir()
            val photoPrefix = "avatar_${dialogId}_"
            val videoPrefix = "avatar_vid_${dialogId}_"
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(photoPrefix) && f.name != keepPhotoName) {
                    f.delete()
                } else if (f.name.startsWith(videoPrefix) && f.name != keepVideoName) {
                    f.delete()
                }
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun scaleBitmapTo4k(src: Bitmap): Bitmap {
        val curMax = max(src.width, src.height)
        if (curMax <= MAX_AVATAR_4K_PX) {
            return src
        }
        val ratio = MAX_AVATAR_4K_PX.toFloat() / curMax.toFloat()
        val dstW = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val dstH = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }

    private fun importAvatarFromUri(uri: Uri, dialogId: Long, account: Int, fragment: BaseFragment?) {
        Thread {
            try {
                val cr = ApplicationLoader.applicationContext.contentResolver
                val mime = cr.getType(uri)?.lowercase(Locale.ROOT).orEmpty()
                val uriStr = uri.toString().lowercase(Locale.ROOT)
                val isVideo = mime.startsWith("video/") ||
                    uriStr.endsWith(".mp4") ||
                    uriStr.endsWith(".mov") ||
                    uriStr.endsWith(".webm") ||
                    uriStr.endsWith(".mkv")

                if (isVideo) {
                    val ts = System.currentTimeMillis()
                    val vidFile = File(avatarStorageDir(), "avatar_vid_${dialogId}_${ts}.mp4")
                    cr.openInputStream(uri)?.use { input ->
                        FileOutputStream(vidFile).use { output ->
                            input.copyTo(output, 65536)
                            output.flush()
                        }
                    }
                    if (!vidFile.exists() || vidFile.length() <= 0L) {
                        toast("MediaTok: не удалось прочитать видео")
                        return@Thread
                    }
                    var coverBmp: Bitmap? = null
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(vidFile.absolutePath)
                        coverBmp = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.frameAtTime
                    } catch (e: Throwable) {
                        FileLog.e(e)
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Throwable) {
                        }
                    }
                    if (coverBmp == null) {
                        vidFile.delete()
                        toast("MediaTok: не удалось извлечь кадр из видео")
                        return@Thread
                    }
                    val scaledCover = scaleBitmapTo4k(coverBmp)
                    if (scaledCover !== coverBmp) {
                        coverBmp.recycle()
                        coverBmp = scaledCover
                    }
                    val outW = coverBmp.width
                    val outH = coverBmp.height
                    val coverFile = File(avatarStorageDir(), "avatar_${dialogId}_${ts}.jpg")
                    FileOutputStream(coverFile).use { fos ->
                        coverBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        fos.flush()
                    }
                    coverBmp.recycle()
                    deleteOldAvatarFiles(dialogId, keepPhotoName = coverFile.name, keepVideoName = vidFile.name)

                    val nowSec = System.currentTimeMillis() / 1000L
                    AndroidUtilities.runOnUIThread {
                        val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                        cur.avatarPath = coverFile.absolutePath
                        cur.avatarVideoPath = vidFile.absolutePath
                        cur.remoteUpdatedAt = nowSec
                        saveOverride(dialogId, cur)
                        notifyPeerChanged(account, dialogId, fragment)
                        toast("MediaTok: установлена 4K видео-ава (${outW}×${outH})")
                    }
                    publishGlobal4kAvatarAsync(account, dialogId, coverFile, vidFile, nowSec)
                    return@Thread
                }

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
                val maxDim = max(rawW, rawH)
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

                val scaled = scaleBitmapTo4k(bmp)
                if (scaled !== bmp) {
                    bmp.recycle()
                    bmp = scaled
                }

                val outW = bmp.width
                val outH = bmp.height
                val ts = System.currentTimeMillis()
                val outFile = File(avatarStorageDir(), "avatar_${dialogId}_${ts}.jpg")
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    fos.flush()
                }
                bmp.recycle()
                deleteOldAvatarFiles(dialogId, keepPhotoName = outFile.name, keepVideoName = null)

                val absPath = outFile.absolutePath
                val nowSec = System.currentTimeMillis() / 1000L
                AndroidUtilities.runOnUIThread {
                    val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                    cur.avatarPath = absPath
                    cur.avatarVideoPath = ""
                    cur.remoteUpdatedAt = nowSec
                    saveOverride(dialogId, cur)
                    notifyPeerChanged(account, dialogId, fragment)
                    toast("MediaTok: установлен 4K аватар (${outW}×${outH})")
                }
                publishGlobal4kAvatarAsync(account, dialogId, outFile, null, nowSec)
            } catch (e: Throwable) {
                FileLog.e(e)
                toast("MediaTok: не удалось сохранить аватар (${e.message})")
            }
        }.start()
    }

    @JvmStatic
    fun onNativeAvatarUploaded(account: Int, dialogId: Long, bitmap: Bitmap?, videoPath: String?) {
        if (dialogId == 0L || (bitmap == null && videoPath.isNullOrBlank())) return
        val copyBmp = try {
            if (bitmap != null && !bitmap.isRecycled) {
                val cfg = bitmap.config ?: Bitmap.Config.ARGB_8888
                bitmap.copy(cfg, false)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        Thread {
            try {
                val ts = System.currentTimeMillis()
                var savedPhoto: File? = null
                var savedVideo: File? = null
                if (!videoPath.isNullOrBlank()) {
                    val srcVid = File(videoPath)
                    if (srcVid.exists() && srcVid.length() > 0L) {
                        val dstVid = File(avatarStorageDir(), "avatar_vid_${dialogId}_${ts}.mp4")
                        srcVid.copyTo(dstVid, overwrite = true)
                        savedVideo = dstVid
                    }
                }
                if (copyBmp != null) {
                    val scaled = scaleBitmapTo4k(copyBmp)
                    val outPhoto = File(avatarStorageDir(), "avatar_${dialogId}_${ts}.jpg")
                    FileOutputStream(outPhoto).use { fos ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 96, fos)
                        fos.flush()
                    }
                    if (scaled !== copyBmp) scaled.recycle()
                    copyBmp.recycle()
                    savedPhoto = outPhoto
                }
                if (savedPhoto == null && savedVideo == null) return@Thread
                deleteOldAvatarFiles(dialogId, keepPhotoName = savedPhoto?.name, keepVideoName = savedVideo?.name)
                val nowSec = System.currentTimeMillis() / 1000L
                AndroidUtilities.runOnUIThread {
                    val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                    if (savedPhoto != null) cur.avatarPath = savedPhoto.absolutePath
                    cur.avatarVideoPath = savedVideo?.absolutePath.orEmpty()
                    cur.remoteUpdatedAt = nowSec
                    saveOverride(dialogId, cur)
                    notifyPeerChanged(account, dialogId, null)
                }
                publishGlobal4kAvatarAsync(account, dialogId, savedPhoto, savedVideo, nowSec)
            } catch (e: Throwable) {
                FileLog.e(e)
            }
        }.start()
    }

    private fun uploadToCatbox(file: File): String? {
        if (!file.exists() || file.length() <= 0L) return null
        var conn: HttpURLConnection? = null
        return try {
            val boundary = "----MediaTok4k${System.currentTimeMillis()}"
            conn = (URL("https://catbox.moe/user/api.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "MediaTok/4K")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n")
                out.writeBytes("fileupload\r\n")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(65536)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            if (conn.responseCode !in 200..299) return null
            val res = conn.inputStream.bufferedReader().use { it.readText().trim() }
            if (res.startsWith("https://")) res else null
        } catch (e: Throwable) {
            FileLog.e(e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun isSafeCatboxMediaUrl(urlStr: String): Boolean {
        val trimmed = urlStr.trim()
        if (!trimmed.startsWith("https://")) return false
        return try {
            val u = URL(trimmed)
            val path = u.path.orEmpty()
            u.protocol.equals("https", ignoreCase = true)
                && u.host.equals("files.catbox.moe", ignoreCase = true)
                && u.userInfo.isNullOrEmpty()
                && u.port == -1
                && path.startsWith("/")
                && path.length > 2
                && !path.contains("..")
        } catch (_: Throwable) {
            false
        }
    }

    private fun downloadUrlToFile(urlStr: String, dst: File, maxBytes: Long = 15L * 1024L * 1024L): Boolean {
        if (!isSafeCatboxMediaUrl(urlStr)) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 45000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MediaTok/4K")
            }
            if (conn.responseCode !in 200..299) return false
            val declaredLen = conn.contentLengthLong
            if (declaredLen > maxBytes) return false
            var total = 0L
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) {
                            output.flush()
                            dst.delete()
                            return false
                        }
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            dst.exists() && dst.length() in 1L..maxBytes
        } catch (e: Throwable) {
            FileLog.e(e)
            dst.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun publishGlobal4kAvatarAsync(account: Int, dialogId: Long, photoFile: File?, videoFile: File?, ts: Long) {
        if (dialogId != UserConfig.getInstance(account).getClientUserId()) return
        Thread {
            try {
                val pUrl = photoFile?.let { uploadToCatbox(it) }.orEmpty()
                val vUrl = videoFile?.let { uploadToCatbox(it) }.orEmpty()
                if (pUrl.isEmpty() && vUrl.isEmpty()) return@Thread

                val payload = JSONObject().apply {
                    if (pUrl.isNotEmpty()) put("photoUrl", pUrl)
                    if (vUrl.isNotEmpty()) put("videoUrl", vUrl)
                    put("ts", ts)
                }.toString()

                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("https://ntfy.sh/mt4k_v1_${dialogId}").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 15000
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("User-Agent", "MediaTok/4K")
                    }
                    conn.outputStream.use { os ->
                        os.write(payload.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    conn.responseCode
                } finally {
                    conn?.disconnect()
                }

                AndroidUtilities.runOnUIThread {
                    val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                    if (cur.remoteUpdatedAt <= ts) {
                        if (pUrl.isNotEmpty()) cur.remotePhotoUrl = pUrl
                        cur.remoteVideoUrl = vUrl
                        cur.remoteUpdatedAt = ts
                        saveOverride(dialogId, cur)
                    }
                }
            } catch (e: Throwable) {
                FileLog.e(e)
            }
        }.start()
    }

    private fun publishGlobalVisualProfileAsync(dialogId: Long, ov: VisualPeerOverride) {
        if (dialogId == 0L) return
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val ts = System.currentTimeMillis() / 1000L
                val payload = JSONObject().apply {
                    put("ts", ts)
                    put("name", ov.name)
                    put("username", ov.username)
                    put("alsoUsernames", ov.alsoUsernames)
                    put("phone", ov.phone)
                    put("bio", ov.bio)
                    put("badgeMode", ov.badgeMode)
                }.toString()
                conn = (URL("https://ntfy.sh/mtprof_v1_${dialogId}").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "MediaTok/ProfileSync")
                }
                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                conn.responseCode
            } catch (e: Throwable) {
                FileLog.e(e)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun unwrapNtfyProfileJson(line: String): JSONObject? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            val outer = JSONObject(trimmed)
            val msg = outer.optString("message", "")
            if (msg.startsWith("{") && msg.endsWith("}")) {
                val inner = JSONObject(msg)
                if (inner.has("ts")) return inner
            }
            if (outer.has("ts") && (outer.has("badgeMode") || outer.has("name") || outer.has("bio") || outer.has("phone") || outer.has("username") || outer.has("alsoUsernames") || outer.has("steamInput") || outer.has("spotifyInput") || outer.has("discordTag") || outer.has("githubInput") || outer.has("robloxInput"))) {
                outer
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun checkGlobalVisualProfileAsync(account: Int, dialogId: Long) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://ntfy.sh/mtprof_v1_${dialogId}/json?poll=1&since=all").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "MediaTok/ProfileSync")
                }
                if (conn.responseCode !in 200..299) return@Thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                var latestObj: JSONObject? = null
                var latestTs = 0L
                for (line in body.lines()) {
                    val cand = unwrapNtfyProfileJson(line) ?: continue
                    val ts = cand.optLong("ts", 0L)
                    if (ts >= latestTs) {
                        latestTs = ts
                        latestObj = cand
                    }
                }
                val obj = latestObj ?: return@Thread
                if (latestTs <= 0L) return@Thread
                val syncKey = "vis_prof_ts_$dialogId"
                val prevTs = prefs().getLong(syncKey, 0L)
                if (latestTs <= prevTs) return@Thread
                prefs().edit().putLong(syncKey, latestTs).apply()
                AndroidUtilities.runOnUIThread {
                    val cur = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                    if (obj.has("name")) cur.name = obj.optString("name", "")
                    if (obj.has("username")) cur.username = normalizeUsername(obj.optString("username", ""))
                    if (obj.has("alsoUsernames")) cur.alsoUsernames = obj.optString("alsoUsernames", "")
                    val mainPhone = obj.optString("phone", "")
                    val alsoPhones = obj.optString("alsoPhones", "")
                    if (mainPhone.isNotEmpty() || alsoPhones.isNotEmpty()) {
                        cur.phone = listOf(mainPhone, alsoPhones).filter { it.isNotBlank() }.joinToString(" • ")
                    }
                    if (obj.has("bio")) cur.bio = obj.optString("bio", "")
                    if (obj.has("badgeMode")) {
                        cur.badgeMode = obj.optInt("badgeMode", BADGE_DEFAULT).coerceIn(BADGE_DEFAULT, BADGE_HIDE)
                    }
                    MediaTokIntegrationsHelper.applyRemoteProfileJson(dialogId, obj)
                    saveOverride(dialogId, cur)
                    notifyPeerChanged(account, dialogId, null)
                }
            } catch (e: Throwable) {
                FileLog.e(e)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    @JvmStatic
    fun checkGlobal4kAvatarForPeer(account: Int, dialogId: Long) {
        if (dialogId == 0L) return
        val nowMs = System.currentTimeMillis()
        val prevMs = lastGlobalCheckMs[dialogId]
        if (prevMs != null && nowMs - prevMs < GLOBAL_SYNC_TTL_MS) return
        val claimed = if (prevMs == null) {
            lastGlobalCheckMs.putIfAbsent(dialogId, nowMs) == null
        } else {
            lastGlobalCheckMs.replace(dialogId, prevMs, nowMs)
        }
        if (claimed) {
            checkGlobalVisualProfileAsync(account, dialogId)
            Thread {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("https://ntfy.sh/mt4k_v1_${dialogId}/raw?poll=1&since=all").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", "MediaTok/4K")
                    }
                    if (conn.responseCode !in 200..299) return@Thread
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val lines = body.lines().asReversed()
                    var latestObj: JSONObject? = null
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                            try {
                                val cand = JSONObject(trimmed)
                                if (isSafeCatboxMediaUrl(cand.optString("photoUrl", "")) ||
                                    isSafeCatboxMediaUrl(cand.optString("videoUrl", ""))) {
                                    latestObj = cand
                                    break
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    val obj = latestObj ?: return@Thread
                    val remoteTs = obj.optLong("ts", 0L)
                    val maxTs = (System.currentTimeMillis() / 1000L) + 300L
                    val photoUrl = obj.optString("photoUrl", "")
                    val videoUrl = obj.optString("videoUrl", "")
                    val cur = getOverride(dialogId)
                    if (remoteTs <= 0L || remoteTs > maxTs || remoteTs <= (cur?.remoteUpdatedAt ?: 0L)) return@Thread
                    if (photoUrl.isEmpty() && videoUrl.isEmpty()) return@Thread

                    val photoDst = if (isSafeCatboxMediaUrl(photoUrl)) File(avatarStorageDir(), "avatar_${dialogId}_${remoteTs}.jpg") else null
                    val videoDst = if (isSafeCatboxMediaUrl(videoUrl)) File(avatarStorageDir(), "avatar_vid_${dialogId}_${remoteTs}.mp4") else null
                    val photoOk = if (photoDst != null) {
                        downloadUrlToFile(photoUrl, photoDst, 15L * 1024L * 1024L) && run {
                            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(photoDst.absolutePath, b)
                            b.outWidth > 0 && b.outHeight > 0
                        }
                    } else {
                        false
                    }
                    val videoOk = if (videoDst != null) downloadUrlToFile(videoUrl, videoDst, 25L * 1024L * 1024L) else false
                    if (!photoOk && !videoOk) {
                        photoDst?.delete()
                        videoDst?.delete()
                        return@Thread
                    }
                    if (!photoOk) photoDst?.delete()
                    if (!videoOk) videoDst?.delete()

                    deleteOldAvatarFiles(
                        dialogId,
                        keepPhotoName = if (photoOk) photoDst?.name else null,
                        keepVideoName = if (videoOk) videoDst?.name else null
                    )
                    AndroidUtilities.runOnUIThread {
                        val updated = getOverride(dialogId)?.copy() ?: VisualPeerOverride()
                        if (updated.remoteUpdatedAt >= remoteTs) return@runOnUIThread
                        if (photoOk && photoDst != null) updated.avatarPath = photoDst.absolutePath
                        updated.avatarVideoPath = if (videoOk && videoDst != null) videoDst.absolutePath else ""
                        updated.remotePhotoUrl = photoUrl
                        updated.remoteVideoUrl = videoUrl
                        updated.remoteUpdatedAt = remoteTs
                        saveOverride(dialogId, updated)
                        notifyPeerChanged(account, dialogId, null)
                    }
                } catch (e: Throwable) {
                    FileLog.e(e)
                } finally {
                    conn?.disconnect()
                }
            }.start()
        }
    }

    @JvmStatic
    fun pickVisualAvatar(fragment: BaseFragment?, dialogId: Long) {
        if (fragment == null || dialogId == 0L) return
        pendingAvatarDialogId = dialogId
        pendingAccount = fragment.currentAccount
        try {
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            fragment.startActivityForResult(Intent.createChooser(pickIntent, "Выберите 4K фото или видео-аву"), REQUEST_CODE_VISUAL_AVATAR)
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
        val hasVideo4k = getVisualAvatarVideoPath(dialogId) != null
        val avatarInfo = if (hasAvatar4k || hasVideo4k) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (current.avatarPath.isNotEmpty()) {
                BitmapFactory.decodeFile(current.avatarPath, opts)
            }
            val dims = if (opts.outWidth > 0) " (${opts.outWidth}×${opts.outHeight})" else " (активен)"
            if (hasVideo4k) "$dims • 4K видео" else dims
        } else {
            ""
        }

        var parentDialog: AlertDialog? = null

        val pickAvatarBtn = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4))
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP)
            text = "🖼/🎬 Выбрать 4K фото или видео-аву (до 3840px)$avatarInfo"
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

        if (hasAvatar4k || hasVideo4k) {
            val removeAvatarBtn = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(Theme.getColor(Theme.key_text_RedRegular))
                setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(8f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
                background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP)
                text = "✕ Удалить 4K аватар / видео"
            }
            removeAvatarBtn.setOnClickListener {
                deleteOldAvatarFiles(dialogId)
                current.avatarPath = ""
                current.avatarVideoPath = ""
                saveOverride(dialogId, current)
                notifyPeerChanged(account, dialogId, fragment)
                removeAvatarBtn.visibility = View.GONE
                pickAvatarBtn.text = "🖼/🎬 Выбрать 4K фото или видео-аву (до 3840px)"
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
        val prev = getOverride(dialogId)
        val ov = VisualPeerOverride(
            name = name.trim(),
            username = mainToken,
            alsoUsernames = alsoList.joinToString(", "),
            bio = bio.trim(),
            phone = phone.trim(),
            badgeMode = badgeMode,
            avatarPath = avatarPath,
            avatarVideoPath = prev?.avatarVideoPath.orEmpty(),
            remotePhotoUrl = prev?.remotePhotoUrl.orEmpty(),
            remoteVideoUrl = prev?.remoteVideoUrl.orEmpty(),
            remoteUpdatedAt = prev?.remoteUpdatedAt ?: 0L
        )
        saveOverride(dialogId, ov)
        if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
            publishGlobalVisualProfileAsync(dialogId, ov)
        }
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
