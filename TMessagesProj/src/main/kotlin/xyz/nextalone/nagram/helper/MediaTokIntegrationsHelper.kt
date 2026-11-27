package xyz.nextalone.nagram.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PeerIntegrations(
    var steamInput: String = "",
    var spotifyInput: String = "",
    var discordTag: String = "",
    var youtubeInput: String = "",
    var robloxInput: String = "",
    var twitchInput: String = "",
    var githubInput: String = "",
    var tiktokInput: String = "",
    var kickInput: String = ""
) {
    fun isEmpty(): Boolean =
        steamInput.isEmpty() &&
            spotifyInput.isEmpty() &&
            discordTag.isEmpty() &&
            youtubeInput.isEmpty() &&
            robloxInput.isEmpty() &&
            twitchInput.isEmpty() &&
            githubInput.isEmpty() &&
            tiktokInput.isEmpty() &&
            kickInput.isEmpty()
}

data class IntegrationCardModel(
    val id: String,
    val platform: String,
    val username: String,
    val memberSinceText: String?,
    val primaryStatBold: String?,
    val primaryStatLabel: String?,
    val secondaryStatBold: String?,
    val secondaryStatLabel: String?,
    val profileUrl: String
)

enum class IntegrationPlatform(
    val key: String,
    val title: String,
    val authorizeEndpoint: String,
    val tokenEndpoint: String,
    val scope: String
) {
    STEAM("steam", "Steam", "https://steamcommunity.com/openid/login", "", ""),
    SPOTIFY("spotify", "Spotify", "https://accounts.spotify.com/authorize", "https://accounts.spotify.com/api/token", "user-read-private user-read-currently-playing"),
    DISCORD("discord", "Discord", "https://discord.com/oauth2/authorize", "https://discord.com/api/oauth2/token", "identify"),
    YOUTUBE("youtube", "YouTube", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/auth/youtube.readonly"),
    ROBLOX("roblox", "Roblox", "https://apis.roblox.com/oauth/v1/authorize", "https://apis.roblox.com/oauth/v1/token", "openid profile"),
    TWITCH("twitch", "Twitch", "https://id.twitch.tv/oauth2/authorize", "https://id.twitch.tv/oauth2/token", "user:read:email"),
    GITHUB("github", "GitHub", "https://github.com/login/oauth/authorize", "https://github.com/login/oauth/access_token", "read:user");

    val redirectUri: String get() = "mediatok://oauth/$key"
}

object MediaTokIntegrationsHelper {

    private const val PREFS_NAME = "mediatok_prefs"
    private const val PREFIX_INT = "mt_integrations_"
    private const val PREFIX_CLIENT_ID = "mt_oauth_cid_"

    private val localCache = ConcurrentHashMap<Long, PeerIntegrations>()
    private val resolvedCache = ConcurrentHashMap<String, ResolvedConn>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val parsedPaths = ConcurrentHashMap<String, Path>()

    private data class ResolvedConn(
        val username: String,
        val memberSince: String?,
        val primaryBold: String?,
        val primaryLabel: String?,
        val secondaryBold: String?,
        val secondaryLabel: String?,
        val url: String,
        val fetchedAtMs: Long
    )

    private data class PendingPkce(
        val platform: IntegrationPlatform,
        val clientId: String,
        val verifier: String,
        val state: String,
        val account: Int,
        val dialogId: Long
    )

    @Volatile
    private var pendingPkce: PendingPkce? = null

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // MARK: - Official 24x24 Simple Icons SVG Non-Zero Winding Paths (1:1 Discord Brand Logos)

    private val svgPaths: Map<String, String> = mapOf(
        "steam" to "M 11.98 0 C 5.68 0 0.51 4.86 0.02 11.04 L 6.45 13.7 C 7 13.32 7.66 13.11 8.37 13.11 C 8.43 13.11 8.49 13.11 8.55 13.11 L 11.42 8.97 L 11.42 8.91 C 11.42 6.42 13.44 4.39 15.94 4.39 C 18.43 4.39 20.46 6.42 20.46 8.91 C 20.46 11.41 18.43 13.44 15.94 13.44 L 15.83 13.44 L 11.76 16.35 C 11.76 16.4 11.76 16.45 11.76 16.51 C 11.76 18.38 10.25 19.9 8.37 19.9 C 6.74 19.9 5.36 18.73 5.04 17.18 L 0.44 15.27 C 1.86 20.31 6.49 24 11.98 24 C 18.61 24 23.98 18.63 23.98 12 C 23.98 5.37 18.61 0 11.98 0 Z M 7.54 18.21 L 6.07 17.6 C 6.33 18.14 6.78 18.6 7.38 18.85 C 8.68 19.39 10.17 18.77 10.71 17.48 C 10.98 16.85 10.98 16.16 10.72 15.53 C 10.46 14.9 9.97 14.41 9.34 14.14 C 8.72 13.88 8.05 13.89 7.46 14.11 L 8.99 14.74 C 9.94 15.14 10.4 16.24 10 17.2 C 9.6 18.16 8.5 18.61 7.54 18.21 L 7.54 18.21 Z M 18.95 8.91 C 18.95 7.25 17.6 5.89 15.94 5.89 C 14.27 5.89 12.92 7.24 12.92 8.91 C 12.92 10.57 14.27 11.92 15.94 11.92 C 17.6 11.92 18.95 10.57 18.95 8.91 Z M 13.68 8.9 C 13.68 7.65 14.69 6.64 15.95 6.64 C 17.2 6.64 18.21 7.65 18.21 8.9 C 18.21 10.15 17.2 11.17 15.95 11.17 C 14.69 11.17 13.68 10.15 13.68 8.9 Z",
        "spotify" to "M 12 0 C 5.4 0 0 5.4 0 12 C 0 18.6 5.4 24 12 24 C 18.6 24 24 18.6 24 12 C 24 5.4 18.66 0 12 0 Z M 17.52 17.34 C 17.28 17.7 16.86 17.82 16.5 17.58 C 13.68 15.84 10.14 15.48 5.94 16.44 C 5.52 16.56 5.16 16.26 5.04 15.9 C 4.92 15.48 5.22 15.12 5.58 15 C 10.14 13.98 14.1 14.4 17.22 16.32 C 17.64 16.5 17.7 16.98 17.52 17.34 Z M 18.96 14.04 C 18.66 14.46 18.12 14.64 17.7 14.34 C 14.46 12.36 9.54 11.76 5.76 12.96 C 5.28 13.08 4.74 12.84 4.62 12.36 C 4.5 11.88 4.74 11.34 5.22 11.22 C 9.6 9.9 15 10.56 18.72 12.84 C 19.08 13.02 19.26 13.62 18.96 14.04 Z M 19.08 10.68 C 15.24 8.4 8.82 8.16 5.16 9.3 C 4.56 9.48 3.96 9.12 3.78 8.58 C 3.6 7.98 3.96 7.38 4.5 7.2 C 8.76 5.94 15.78 6.18 20.22 8.82 C 20.76 9.12 20.94 9.84 20.64 10.38 C 20.34 10.8 19.62 10.98 19.08 10.68 Z",
        "discord" to "M 20.32 4.37 C 18.76 3.66 17.12 3.15 15.43 2.85 C 15.4 2.85 15.37 2.86 15.35 2.89 C 15.14 3.27 14.91 3.76 14.75 4.14 C 12.9 3.86 11.07 3.86 9.26 4.14 C 9.09 3.75 8.85 3.27 8.64 2.89 C 8.62 2.86 8.59 2.85 8.56 2.85 C 6.87 3.15 5.23 3.65 3.68 4.37 C 3.66 4.38 3.65 4.39 3.64 4.4 C 0.53 9.05 -0.32 13.58 0.1 18.06 C 0.1 18.08 0.11 18.1 0.13 18.11 C 2.18 19.62 4.17 20.54 6.12 21.14 C 6.15 21.15 6.19 21.14 6.21 21.12 C 6.67 20.49 7.08 19.82 7.43 19.12 C 7.44 19.1 7.44 19.08 7.44 19.06 C 7.43 19.04 7.41 19.02 7.39 19.02 C 6.74 18.77 6.12 18.47 5.52 18.12 C 5.5 18.11 5.48 18.09 5.48 18.06 C 5.48 18.04 5.49 18.01 5.51 18 C 5.64 17.9 5.76 17.8 5.88 17.7 C 5.91 17.69 5.94 17.68 5.96 17.69 C 9.89 19.49 14.14 19.49 18.02 17.69 C 18.05 17.68 18.08 17.69 18.1 17.7 C 18.22 17.8 18.35 17.9 18.47 18 C 18.49 18.01 18.51 18.04 18.5 18.06 C 18.5 18.09 18.49 18.11 18.47 18.12 C 17.87 18.47 17.24 18.77 16.59 19.01 C 16.57 19.02 16.56 19.04 16.55 19.06 C 16.54 19.08 16.54 19.1 16.55 19.12 C 16.91 19.82 17.33 20.48 17.78 21.11 C 17.8 21.14 17.83 21.15 17.86 21.14 C 19.82 20.54 21.81 19.62 23.87 18.11 C 23.88 18.1 23.89 18.08 23.9 18.06 C 24.4 12.88 23.06 8.38 20.35 4.4 C 20.34 4.39 20.33 4.38 20.32 4.37 Z M 8.02 15.33 C 6.84 15.33 5.86 14.25 5.86 12.91 C 5.86 11.58 6.82 10.49 8.02 10.49 C 9.23 10.49 10.2 11.59 10.18 12.91 C 10.18 14.25 9.22 15.33 8.02 15.33 Z M 15.99 15.33 C 14.81 15.33 13.84 14.25 13.84 12.91 C 13.84 11.58 14.79 10.49 15.99 10.49 C 17.21 10.49 18.17 11.59 18.15 12.91 C 18.15 14.25 17.21 15.33 15.99 15.33 Z",
        "youtube" to "M 23.5 6.19 C 23.22 5.15 22.41 4.33 21.38 4.05 C 19.5 3.54 12 3.54 12 3.54 C 12 3.54 4.5 3.54 2.62 4.05 C 1.59 4.33 0.78 5.15 0.5 6.19 C 0 8.07 0 12 0 12 C 0 12 0 15.93 0.5 17.81 C 0.78 18.85 1.59 19.67 2.62 19.95 C 4.49 20.45 12 20.45 12 20.45 C 12 20.45 19.5 20.45 21.38 19.95 C 22.41 19.67 23.22 18.85 23.5 17.81 C 24 15.93 24 12 24 12 C 24 12 24 8.07 23.5 6.19 Z M 9.54 15.57 L 9.54 8.43 L 15.82 12 L 9.54 15.57 Z",
        "roblox" to "M 18.93 24 L 0 18.89 L 5.08 0 L 24 5.11 Z M 15.35 10.09 L 10.07 8.64 L 8.65 13.91 L 13.93 15.36 Z",
        "twitch" to "M 11.57 4.71 L 13.29 4.71 L 13.29 9.86 L 11.57 9.86 Z M 16.29 4.71 L 18 4.71 L 18 9.86 L 16.29 9.86 Z M 6 0 L 1.71 4.29 L 1.71 19.71 L 6.86 19.71 L 6.86 24 L 11.14 19.71 L 14.57 19.71 L 22.29 12 L 22.29 0 Z M 20.57 11.14 L 17.14 14.57 L 13.71 14.57 L 10.71 17.57 L 10.71 14.57 L 6.86 14.57 L 6.86 1.71 L 20.57 1.71 Z",
        "github" to "M 12 0.3 C 5.37 0.3 0 5.67 0 12.3 C 0 17.6 3.44 22.1 8.21 23.68 C 8.8 23.8 9.03 23.42 9.03 23.11 C 9.03 22.82 9.02 22.07 9.01 21.07 C 5.67 21.79 4.97 19.46 4.97 19.46 C 4.42 18.07 3.63 17.7 3.63 17.7 C 2.55 16.96 3.72 16.97 3.72 16.97 C 4.92 17.05 5.55 18.21 5.55 18.21 C 6.62 20.04 8.36 19.51 9.05 19.21 C 9.16 18.43 9.47 17.9 9.81 17.6 C 7.15 17.3 4.34 16.27 4.34 11.67 C 4.34 10.36 4.81 9.29 5.58 8.45 C 5.44 8.15 5.04 6.93 5.68 5.27 C 5.68 5.27 6.69 4.95 8.98 6.5 C 9.94 6.24 10.96 6.11 11.98 6.1 C 13 6.11 14.02 6.24 14.98 6.5 C 17.26 4.95 18.27 5.27 18.27 5.27 C 18.91 6.93 18.51 8.15 18.39 8.45 C 19.15 9.29 19.62 10.36 19.62 11.67 C 19.62 16.28 16.81 17.3 14.14 17.59 C 14.56 17.95 14.95 18.69 14.95 19.81 C 14.95 21.42 14.94 22.71 14.94 23.1 C 14.94 23.41 15.15 23.79 15.76 23.67 C 20.57 22.09 24 17.59 24 12.3 C 24 5.67 18.63 0.3 12 0.3",
        "tiktok" to "M 12.53 0.02 C 13.84 0 15.13 0.01 16.44 0 C 16.52 1.53 17.07 3.09 18.19 4.17 C 19.31 5.28 20.89 5.79 22.43 5.96 L 22.43 9.99 C 20.99 9.94 19.54 9.64 18.23 9.02 C 17.66 8.76 17.13 8.43 16.61 8.09 C 16.6 11.01 16.62 13.93 16.59 16.84 C 16.51 18.24 16.05 19.63 15.24 20.78 C 13.93 22.7 11.66 23.95 9.33 23.99 C 7.9 24.07 6.47 23.68 5.25 22.96 C 3.23 21.77 1.81 19.59 1.6 17.25 C 1.58 16.75 1.57 16.25 1.59 15.76 C 1.77 13.86 2.71 12.04 4.17 10.8 C 5.83 9.36 8.15 8.67 10.32 9.08 C 10.34 10.56 10.28 12.04 10.28 13.52 C 9.29 13.2 8.13 13.29 7.26 13.89 C 6.63 14.3 6.15 14.93 5.9 15.64 C 5.69 16.15 5.75 16.71 5.76 17.25 C 6 18.89 7.58 20.27 9.26 20.12 C 10.38 20.11 11.45 19.46 12.03 18.51 C 12.22 18.18 12.43 17.84 12.44 17.45 C 12.54 15.66 12.5 13.88 12.51 12.09 C 12.52 8.06 12.5 4.04 12.53 0.02 Z",
        "kick" to "M 1.33 0 L 9.33 0 L 9.33 5.33 L 12 5.33 L 12 2.67 L 14.67 2.67 L 14.67 0 L 22.67 0 L 22.67 8 L 20 8 L 20 10.67 L 17.33 10.67 L 17.33 13.33 L 20 13.33 L 20 16 L 22.67 16 L 22.67 24 L 14.67 24 L 14.67 21.33 L 12 21.33 L 12 18.67 L 9.33 18.67 L 9.33 24 L 1.33 24 Z"
    )

    private fun getUnitPath(platform: String): Path? {
        val key = platform.lowercase(Locale.ROOT)
        parsedPaths[key]?.let { return it }
        val encoded = svgPaths[key] ?: return null
        val path = Path()
        path.fillType = Path.FillType.WINDING
        val tokens = encoded.split(' ')
        var i = 0
        fun nextF(): Float {
            val v = tokens[i].toFloatOrNull() ?: 0f
            i++
            return v
        }
        while (i < tokens.size) {
            when (tokens[i++]) {
                "M" -> path.moveTo(nextF(), nextF())
                "L" -> path.lineTo(nextF(), nextF())
                "C" -> path.cubicTo(nextF(), nextF(), nextF(), nextF(), nextF(), nextF())
                "Z" -> path.close()
            }
        }
        parsedPaths[key] = path
        return path
    }

    class BrandIconView(context: Context) : View(context) {
        private var platform: String = "steam"
        private var isDark: Boolean = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val tmpPath = Path()
        private val matrix = Matrix()
        private val rectF = RectF()

        fun setBrand(platform: String, isDark: Boolean) {
            this.platform = platform.lowercase(Locale.ROOT)
            this.isDark = isDark
            invalidate()
        }

        private fun drawGlyph(canvas: Canvas, unit: Path, color: Int, inset: Float, dx: Float = 0f, dy: Float = 0f) {
            val size = minOf(width, height).toFloat()
            val avail = size - inset * 2f
            if (avail <= 1f) return
            val sc = avail / 24f
            tmpPath.set(unit)
            matrix.reset()
            matrix.postScale(sc, sc)
            matrix.postTranslate(inset + dx, inset + dy)
            tmpPath.transform(matrix)
            paint.color = color
            canvas.drawPath(tmpPath, paint)
        }

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height).toFloat()
            if (size <= 2f) return
            val unit = getUnitPath(platform) ?: return

            when (platform) {
                "steam" -> drawGlyph(canvas, unit, if (isDark) Color.WHITE else 0xFF171A21.toInt(), size * 0.03f)
                "spotify" -> drawGlyph(canvas, unit, 0xFF1ED760.toInt(), size * 0.03f)
                "github" -> drawGlyph(canvas, unit, if (isDark) Color.WHITE else 0xFF181717.toInt(), size * 0.03f)
                "roblox" -> drawGlyph(canvas, unit, if (isDark) Color.WHITE else 0xFF1E1F22.toInt(), size * 0.06f)
                "youtube" -> {
                    paint.color = Color.WHITE
                    canvas.drawRect(size * 0.34f, size * 0.32f, size * 0.68f, size * 0.68f, paint)
                    drawGlyph(canvas, unit, 0xFFFF0000.toInt(), size * 0.03f)
                }
                "discord" -> {
                    paint.color = 0xFF5865F2.toInt()
                    rectF.set(size * 0.03f, size * 0.03f, size * 0.97f, size * 0.97f)
                    canvas.drawRoundRect(rectF, size * 0.28f, size * 0.28f, paint)
                    drawGlyph(canvas, unit, Color.WHITE, size * 0.18f)
                }
                "twitch" -> {
                    paint.color = Color.WHITE
                    canvas.drawRect(size * 0.28f, size * 0.14f, size * 0.84f, size * 0.64f, paint)
                    drawGlyph(canvas, unit, 0xFF9146FF.toInt(), size * 0.04f)
                }
                "tiktok" -> {
                    paint.color = 0xFF121214.toInt()
                    rectF.set(size * 0.03f, size * 0.03f, size * 0.97f, size * 0.97f)
                    canvas.drawRoundRect(rectF, size * 0.28f, size * 0.28f, paint)
                    val sh = size * 0.032f
                    drawGlyph(canvas, unit, 0xFF25F4EE.toInt(), size * 0.19f, -sh, -sh * 0.8f)
                    drawGlyph(canvas, unit, 0xFFFE2C55.toInt(), size * 0.19f, sh, sh * 0.8f)
                    drawGlyph(canvas, unit, Color.WHITE, size * 0.19f)
                }
                "kick" -> {
                    paint.color = 0xFF121214.toInt()
                    rectF.set(size * 0.03f, size * 0.03f, size * 0.97f, size * 0.97f)
                    canvas.drawRoundRect(rectF, size * 0.28f, size * 0.28f, paint)
                    drawGlyph(canvas, unit, 0xFF53FC18.toInt(), size * 0.22f)
                }
                else -> drawGlyph(canvas, unit, if (isDark) Color.WHITE else Color.BLACK, size * 0.06f)
            }
        }
    }

    // MARK: - Diagonal Arrow Vector View (↗)

    private class DiagonalArrowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun setTint(color: Int) {
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 2f || h <= 2f) return
            paint.strokeWidth = AndroidUtilities.dpf2(1.7f)
            val pad = w * 0.22f
            path.reset()
            path.moveTo(pad, h - pad)
            path.lineTo(w - pad, pad)
            path.moveTo(w * 0.42f, pad)
            path.lineTo(w - pad, pad)
            path.lineTo(w - pad, h * 0.58f)
            canvas.drawPath(path, paint)
        }
    }

    // MARK: - Native Discord-Style Integration Card Cell (Zero Checkmark, Zero AI Slop)

    class IntegrationCardCell(context: Context, private val resourcesProvider: Theme.ResourcesProvider?) : FrameLayout(context) {
        private val cardBg = LinearLayout(context)
        private val iconView = BrandIconView(context)
        private val usernameTv = TextView(context)
        private val memberSinceTv = TextView(context)
        private val statsTv = TextView(context)
        private val arrowView = DiagonalArrowView(context)
        private var currentCard: IntegrationCardModel? = null

        init {
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(4f), AndroidUtilities.dp(12f), AndroidUtilities.dp(4f))

            cardBg.orientation = LinearLayout.HORIZONTAL
            cardBg.gravity = Gravity.CENTER_VERTICAL
            cardBg.setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(12f), AndroidUtilities.dp(14f), AndroidUtilities.dp(12f))
            addView(cardBg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))

            cardBg.addView(iconView, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 0, 0, 12, 0))

            val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            usernameTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f)
            usernameTv.typeface = AndroidUtilities.bold()
            usernameTv.maxLines = 1
            usernameTv.ellipsize = TextUtils.TruncateAt.END
            textCol.addView(usernameTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            memberSinceTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
            memberSinceTv.maxLines = 1
            memberSinceTv.ellipsize = TextUtils.TruncateAt.END
            textCol.addView(memberSinceTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 1.5f, 0f, 0f))

            statsTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
            statsTv.maxLines = 1
            statsTv.ellipsize = TextUtils.TruncateAt.END
            textCol.addView(statsTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))

            cardBg.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            cardBg.addView(arrowView, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 10, 0, 0, 0))

            setOnClickListener {
                val c = currentCard ?: return@setOnClickListener
                if (c.platform == "discord" || c.profileUrl.isBlank()) {
                    copyToClipboard(context, c.username)
                } else {
                    try {
                        val uri = Uri.parse(c.profileUrl)
                        if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } else {
                            copyToClipboard(context, c.username)
                        }
                    } catch (_: Throwable) {
                        copyToClipboard(context, c.profileUrl)
                    }
                }
            }
            setOnLongClickListener {
                val c = currentCard ?: return@setOnLongClickListener false
                copyToClipboard(context, if (c.profileUrl.isNotBlank()) c.profileUrl else c.username)
                true
            }
        }

        fun bind(card: IntegrationCardModel) {
            currentCard = card
            val bgColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)
            val isDark = AndroidUtilities.computePerceivedBrightness(bgColor) < 0.5f
            val primaryColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
            val secondaryColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider)

            val cardFill = if (isDark) 0xFF1E1F22.toInt() else 0xFFF2F3F5.toInt()
            val strokeColor = if (isDark) 0x14FFFFFF else 0x12000000
            cardBg.background = GradientDrawable().apply {
                setColor(cardFill)
                cornerRadius = AndroidUtilities.dpf2(16f)
                setStroke(AndroidUtilities.dp(1f), strokeColor)
            }

            iconView.setBrand(card.platform, isDark)
            usernameTv.text = card.username
            usernameTv.setTextColor(primaryColor)

            if (!card.memberSinceText.isNullOrBlank()) {
                memberSinceTv.text = card.memberSinceText
                memberSinceTv.setTextColor(secondaryColor)
                memberSinceTv.visibility = View.VISIBLE
            } else {
                memberSinceTv.visibility = View.GONE
            }

            if (!card.primaryStatBold.isNullOrBlank()) {
                val sb = SpannableStringBuilder()
                val s0 = sb.length
                sb.append(card.primaryStatBold)
                sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(primaryColor), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (!card.primaryStatLabel.isNullOrBlank()) {
                    val s1 = sb.length
                    sb.append(" ").append(card.primaryStatLabel)
                    sb.setSpan(ForegroundColorSpan(secondaryColor), s1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (!card.secondaryStatBold.isNullOrBlank()) {
                    val sepStart = sb.length
                    sb.append("  ·  ")
                    sb.setSpan(ForegroundColorSpan(secondaryColor), sepStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val s2 = sb.length
                    sb.append(card.secondaryStatBold)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(primaryColor), s2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (!card.secondaryStatLabel.isNullOrBlank()) {
                        val s3 = sb.length
                        sb.append(" ").append(card.secondaryStatLabel)
                        sb.setSpan(ForegroundColorSpan(secondaryColor), s3, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                statsTv.text = sb
                statsTv.visibility = View.VISIBLE
            } else {
                statsTv.visibility = View.GONE
            }

            arrowView.setTint(secondaryColor)
            arrowView.visibility = if (card.profileUrl.isNotBlank() && card.platform != "discord") View.VISIBLE else View.GONE
        }
    }

    // MARK: - Persistence & Cross-Platform Cloud Sync (mtprof_v1)

    @JvmStatic
    fun getIntegrations(dialogId: Long): PeerIntegrations {
        if (dialogId == 0L) return PeerIntegrations()
        localCache[dialogId]?.let { return it }
        val raw = prefs().getString(PREFIX_INT + dialogId, null)
        val parsed = if (raw != null) {
            try {
                val o = JSONObject(raw)
                PeerIntegrations(
                    steamInput = o.optString("steamInput", ""),
                    spotifyInput = o.optString("spotifyInput", ""),
                    discordTag = o.optString("discordTag", ""),
                    youtubeInput = o.optString("youtubeInput", ""),
                    robloxInput = o.optString("robloxInput", ""),
                    twitchInput = o.optString("twitchInput", ""),
                    githubInput = o.optString("githubInput", ""),
                    tiktokInput = o.optString("tiktokInput", ""),
                    kickInput = o.optString("kickInput", "")
                )
            } catch (_: Throwable) {
                PeerIntegrations()
            }
        } else PeerIntegrations()
        localCache[dialogId] = parsed
        return parsed
    }

    @JvmStatic
    fun saveIntegrations(account: Int, dialogId: Long, data: PeerIntegrations, publishGlobally: Boolean = true) {
        if (dialogId == 0L) return
        localCache[dialogId] = data
        val json = JSONObject().apply {
            put("steamInput", data.steamInput)
            put("spotifyInput", data.spotifyInput)
            put("discordTag", data.discordTag)
            put("youtubeInput", data.youtubeInput)
            put("robloxInput", data.robloxInput)
            put("twitchInput", data.twitchInput)
            put("githubInput", data.githubInput)
            put("tiktokInput", data.tiktokInput)
            put("kickInput", data.kickInput)
        }.toString()
        prefs().edit().putString(PREFIX_INT + dialogId, json).apply()
        if (publishGlobally && dialogId == UserConfig.getInstance(account).getClientUserId()) {
            publishIntegrationsToCloud(dialogId, data)
        }
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, 0xFFFF)
        }
    }

    @JvmStatic
    fun applyRemoteProfileJson(dialogId: Long, obj: JSONObject) {
        if (dialogId == 0L) return
        val hasAny = obj.has("steamInput") || obj.has("spotifyInput") || obj.has("discordTag") ||
            obj.has("youtubeInput") || obj.has("robloxInput") || obj.has("twitchInput") ||
            obj.has("githubInput") || obj.has("tiktokInput") || obj.has("kickInput")
        if (!hasAny) return
        val cur = getIntegrations(dialogId).copy()
        if (obj.has("steamInput")) cur.steamInput = obj.optString("steamInput", "").take(120)
        if (obj.has("spotifyInput")) cur.spotifyInput = obj.optString("spotifyInput", "").take(140)
        if (obj.has("discordTag")) cur.discordTag = obj.optString("discordTag", "").take(64)
        if (obj.has("youtubeInput")) cur.youtubeInput = obj.optString("youtubeInput", "").take(80)
        if (obj.has("robloxInput")) cur.robloxInput = obj.optString("robloxInput", "").take(120)
        if (obj.has("twitchInput")) cur.twitchInput = obj.optString("twitchInput", "").take(80)
        if (obj.has("githubInput")) cur.githubInput = obj.optString("githubInput", "").take(80)
        if (obj.has("tiktokInput")) cur.tiktokInput = obj.optString("tiktokInput", "").take(80)
        if (obj.has("kickInput")) cur.kickInput = obj.optString("kickInput", "").take(80)
        localCache[dialogId] = cur
        val json = JSONObject().apply {
            put("steamInput", cur.steamInput)
            put("spotifyInput", cur.spotifyInput)
            put("discordTag", cur.discordTag)
            put("youtubeInput", cur.youtubeInput)
            put("robloxInput", cur.robloxInput)
            put("twitchInput", cur.twitchInput)
            put("githubInput", cur.githubInput)
            put("tiktokInput", cur.tiktokInput)
            put("kickInput", cur.kickInput)
        }.toString()
        prefs().edit().putString(PREFIX_INT + dialogId, json).apply()
    }

    private fun publishIntegrationsToCloud(dialogId: Long, data: PeerIntegrations) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val payload = JSONObject().apply {
                    put("ts", System.currentTimeMillis() / 1000L)
                    put("steamInput", data.steamInput)
                    put("spotifyInput", data.spotifyInput)
                    put("discordTag", data.discordTag)
                    put("youtubeInput", data.youtubeInput)
                    put("robloxInput", data.robloxInput)
                    put("twitchInput", data.twitchInput)
                    put("githubInput", data.githubInput)
                    put("tiktokInput", data.tiktokInput)
                    put("kickInput", data.kickInput)
                }.toString()
                conn = (URL("https://ntfy.sh/mtprof_v1_$dialogId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "MediaTok/Integrations")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (e: Throwable) {
                FileLog.e(e)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    // MARK: - Build Cards for ProfileActivity

    private fun sanitizeHttpsUrl(raw: String, allowedDomains: List<String>, fallback: String = ""): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallback
        return try {
            val u = URL(trimmed)
            val host = u.host.lowercase(Locale.ROOT)
            val allowed = allowedDomains.any { d -> host == d || host.endsWith(".$d") }
            if (u.protocol.equals("https", ignoreCase = true)
                && allowed
                && u.userInfo.isNullOrEmpty()
                && u.port == -1
            ) {
                trimmed
            } else {
                fallback
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    @JvmStatic
    fun buildCardsForPeer(account: Int, dialogId: Long, onRefresh: Runnable?): List<IntegrationCardModel> {
        if (dialogId <= 0L) return emptyList()
        val data = getIntegrations(dialogId)
        if (data.isEmpty()) return emptyList()

        val cards = ArrayList<IntegrationCardModel>()
        if (data.steamInput.isNotBlank()) {
            val r = resolveSteam(data.steamInput, onRefresh)
            cards.add(IntegrationCardModel("steam", "steam", r.username, r.memberSince, r.primaryBold, r.primaryLabel, r.secondaryBold, r.secondaryLabel, r.url))
        }
        if (data.spotifyInput.isNotBlank()) {
            val parts = data.spotifyInput.split("|", limit = 2)
            val name = parts[0].trim().ifEmpty { "Spotify" }
            val rawUrl = if (parts.size > 1) parts[1].trim() else "https://open.spotify.com/"
            val url = sanitizeHttpsUrl(rawUrl, listOf("spotify.com"), "https://open.spotify.com/")
            cards.add(IntegrationCardModel("spotify", "spotify", name, "Spotify", null, null, null, null, url))
        }
        if (data.robloxInput.isNotBlank()) {
            val r = resolveRoblox(data.robloxInput, onRefresh)
            cards.add(IntegrationCardModel("roblox", "roblox", r.username, r.memberSince, r.primaryBold, r.primaryLabel, r.secondaryBold, r.secondaryLabel, r.url))
        }
        if (data.githubInput.isNotBlank()) {
            val r = resolveGithub(data.githubInput, onRefresh)
            cards.add(IntegrationCardModel("github", "github", r.username, r.memberSince, r.primaryBold, r.primaryLabel, r.secondaryBold, r.secondaryLabel, r.url))
        }
        if (data.twitchInput.isNotBlank()) {
            val clean = data.twitchInput.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("twitch.tv/").trim('/')
            val r = resolveTwitch(clean, onRefresh)
            cards.add(IntegrationCardModel("twitch", "twitch", r.username, r.memberSince, r.primaryBold, r.primaryLabel, null, null, r.url))
        }
        if (data.youtubeInput.isNotBlank()) {
            val raw = data.youtubeInput.trim()
            val candidate = if (raw.startsWith("https://", ignoreCase = true)) raw else "https://www.youtube.com/${if (raw.startsWith("@")) raw else "@$raw"}"
            val url = sanitizeHttpsUrl(candidate, listOf("youtube.com", "youtu.be"), "https://www.youtube.com/")
            val name = raw.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("youtube.com/").trim('/')
            cards.add(IntegrationCardModel("youtube", "youtube", name.ifEmpty { "YouTube" }, "YouTube", null, null, null, null, url))
        }
        if (data.discordTag.isNotBlank()) {
            cards.add(IntegrationCardModel("discord", "discord", data.discordTag.trim(), "Discord", null, null, null, null, ""))
        }
        if (data.tiktokInput.isNotBlank()) {
            val handle = data.tiktokInput.trim().removePrefix("@")
            cards.add(IntegrationCardModel("tiktok", "tiktok", "@$handle", "TikTok", null, null, null, null, "https://www.tiktok.com/@$handle"))
        }
        if (data.kickInput.isNotBlank()) {
            val handle = data.kickInput.trim().removePrefix("https://kick.com/").trim('/')
            cards.add(IntegrationCardModel("kick", "kick", handle, "Kick", null, null, null, null, "https://kick.com/$handle"))
        }
        return cards
    }

    // MARK: - Live Resolvers (Steam XML, GitHub API, Roblox API, Twitch)

    private fun formatRussianMemberSince(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        val months = arrayOf("янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")
        if (s.length >= 10 && s[4] == '-' && s[7] == '-') {
            val p = s.substring(0, 10).split('-')
            if (p.size == 3) {
                val y = p[0].toIntOrNull()
                val m = p[1].toIntOrNull()
                val d = p[2].toIntOrNull()
                if (y != null && m != null && d != null && m in 1..12) {
                    return "В числе участников с $d ${months[m - 1]} $y г."
                }
            }
        }
        val cleaned = s.replace(",", "").replace("st", "").replace("nd", "").replace("rd", "").replace("th", "")
        val toks = cleaned.split(Regex("\\s+"))
        val map = mapOf(
            "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
            "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
            "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9,
            "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12
        )
        if (toks.size >= 3) {
            val m1 = map[toks[0].lowercase(Locale.ROOT)]
            val d1 = toks[1].toIntOrNull()
            val y1 = toks[2].toIntOrNull()
            if (m1 != null && d1 != null && y1 != null) {
                return "В числе участников с $d1 ${months[m1 - 1]} $y1 г."
            }
            val d2 = toks[0].toIntOrNull()
            val m2 = map[toks[1].lowercase(Locale.ROOT)]
            if (m2 != null && d2 != null && y1 != null) {
                return "В числе участников с $d2 ${months[m2 - 1]} $y1 г."
            }
        }
        return null
    }

    private fun extractXmlTag(tag: String, xml: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val s = xml.indexOf(open)
        if (s < 0) return null
        val e = xml.indexOf(close, s + open.length)
        if (e < 0) return null
        var v = xml.substring(s + open.length, e).trim()
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) {
            v = v.substring(9, v.length - 3)
        }
        return v.ifEmpty { null }
    }

    private fun resolveSteam(input: String, onRefresh: Runnable?): ResolvedConn {
        val clean = input.trim()
        val candidateUrl = when {
            clean.startsWith("https://", ignoreCase = true) || clean.startsWith("http://", ignoreCase = true) -> clean.trimEnd('/')
            clean.all { it.isDigit() } && clean.length >= 16 -> "https://steamcommunity.com/profiles/$clean"
            else -> "https://steamcommunity.com/id/${Uri.encode(clean)}"
        }
        val profileUrl = sanitizeHttpsUrl(candidateUrl, listOf("steamcommunity.com"), "")
        if (profileUrl.isEmpty()) {
            return ResolvedConn(
                username = "Steam",
                memberSince = "Steam",
                primaryBold = null,
                primaryLabel = null,
                secondaryBold = null,
                secondaryLabel = null,
                url = "https://steamcommunity.com/",
                fetchedAtMs = System.currentTimeMillis()
            )
        }
        val key = "steam:${profileUrl.lowercase(Locale.ROOT)}"
        val now = System.currentTimeMillis()
        val cached = resolvedCache[key]
        if ((cached == null || now - cached.fetchedAtMs > 600_000L) && inFlight.add(key)) {
            Thread {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("$profileUrl?xml=1").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 9000
                        readTimeout = 9000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "MediaTok/Steam")
                    }
                    val xml = conn.inputStream.bufferedReader().use { it.readText() }
                    val nick = extractXmlTag("steamID", xml) ?: profileUrl.substringAfterLast('/')
                    val since = formatRussianMemberSince(extractXmlTag("memberSince", xml))
                    val topGame = extractXmlTag("gameName", xml)
                    val hours = extractXmlTag("hoursPlayed", xml)
                    val hours2Wk = extractXmlTag("hoursPlayed2Wk", xml)
                    val gamesCount = xml.split("<mostPlayedGame>").size - 1

                    var pB: String? = null
                    var pL: String? = null
                    var sB: String? = null
                    var sL: String? = null
                    if (!hours.isNullOrBlank() && !topGame.isNullOrBlank()) {
                        pB = "$hours ч"
                        pL = topGame
                        if (!hours2Wk.isNullOrBlank()) {
                            sB = "$hours2Wk ч"
                            sL = "за 2 нед."
                        }
                    } else if (gamesCount > 0) {
                        pB = "$gamesCount"
                        pL = "Games"
                    }
                    resolvedCache[key] = ResolvedConn(nick, since ?: "Steam", pB, pL, sB, sL, profileUrl, System.currentTimeMillis())
                    onRefresh?.let { AndroidUtilities.runOnUIThread(it) }
                } catch (_: Throwable) {
                } finally {
                    conn?.disconnect()
                    inFlight.remove(key)
                }
            }.start()
        }
        return cached ?: ResolvedConn(profileUrl.substringAfterLast('/'), "Steam", null, null, null, null, profileUrl, 0L)
    }

    private fun resolveGithub(input: String, onRefresh: Runnable?): ResolvedConn {
        val login = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/").trim('/').removePrefix("@")
        val profileUrl = "https://github.com/$login"
        val key = "github:${login.lowercase(Locale.ROOT)}"
        val now = System.currentTimeMillis()
        val cached = resolvedCache[key]
        if ((cached == null || now - cached.fetchedAtMs > 600_000L) && inFlight.add(key)) {
            Thread {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("https://api.github.com/users/${URLEncoder.encode(login, "UTF-8")}").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "MediaTok/GitHub")
                    }
                    if (conn.responseCode in 200..299) {
                        val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val realLogin = obj.optString("login", login)
                        val repos = obj.optInt("public_repos", 0)
                        val followers = obj.optInt("followers", 0)
                        val since = formatRussianMemberSince(obj.optString("created_at", ""))
                        resolvedCache[key] = ResolvedConn(
                            realLogin, since ?: "GitHub",
                            "$repos", "Repos",
                            if (followers > 0) "$followers" else null,
                            if (followers > 0) "Followers" else null,
                            profileUrl, System.currentTimeMillis()
                        )
                        onRefresh?.let { AndroidUtilities.runOnUIThread(it) }
                    }
                } catch (_: Throwable) {
                } finally {
                    conn?.disconnect()
                    inFlight.remove(key)
                }
            }.start()
        }
        return cached ?: ResolvedConn(login, "GitHub", null, null, null, null, profileUrl, 0L)
    }

    private fun resolveRoblox(input: String, onRefresh: Runnable?): ResolvedConn {
        val raw = input.trim()
        val idMatch = Regex("users/(\\d+)").find(raw)?.groupValues?.get(1) ?: raw.takeIf { it.all { c -> c.isDigit() } }
        val fallbackUrl = if (idMatch != null) "https://www.roblox.com/users/$idMatch/profile" else "https://www.roblox.com/"
        val key = "roblox:${raw.lowercase(Locale.ROOT)}"
        val now = System.currentTimeMillis()
        val cached = resolvedCache[key]
        if (idMatch != null && (cached == null || now - cached.fetchedAtMs > 600_000L) && inFlight.add(key)) {
            Thread {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("https://users.roblox.com/v1/users/$idMatch").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    if (conn.responseCode in 200..299) {
                        val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val display = obj.optString("displayName", "")
                        val uname = obj.optString("name", "")
                        val since = formatRussianMemberSince(obj.optString("created", ""))
                        resolvedCache[key] = ResolvedConn(
                            display.ifEmpty { uname.ifEmpty { "ID $idMatch" } },
                            since ?: "Roblox",
                            if (uname.isNotEmpty()) "@$uname" else null,
                            null, null, null, fallbackUrl, System.currentTimeMillis()
                        )
                        onRefresh?.let { AndroidUtilities.runOnUIThread(it) }
                    }
                } catch (_: Throwable) {
                } finally {
                    conn?.disconnect()
                    inFlight.remove(key)
                }
            }.start()
        }
        return cached ?: ResolvedConn(idMatch ?: raw, "Roblox", null, null, null, null, fallbackUrl, 0L)
    }

    private fun resolveTwitch(login: String, onRefresh: Runnable?): ResolvedConn {
        val url = "https://www.twitch.tv/$login"
        val key = "twitch:${login.lowercase(Locale.ROOT)}"
        val now = System.currentTimeMillis()
        val cached = resolvedCache[key]
        if ((cached == null || now - cached.fetchedAtMs > 180_000L) && inFlight.add(key)) {
            Thread {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("https://decapi.me/twitch/uptime/${URLEncoder.encode(login, "UTF-8")}").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    if (conn.responseCode in 200..299) {
                        val txt = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                        val low = txt.lowercase(Locale.ROOT)
                        val offline = low.contains("offline") || low.contains("error") || low.contains("not found") || txt.isEmpty()
                        resolvedCache[key] = ResolvedConn(
                            login, "Twitch",
                            if (offline) "Офлайн" else "В эфире",
                            if (offline) null else txt.take(36),
                            null, null, url, System.currentTimeMillis()
                        )
                        onRefresh?.let { AndroidUtilities.runOnUIThread(it) }
                    }
                } catch (_: Throwable) {
                } finally {
                    conn?.disconnect()
                    inFlight.remove(key)
                }
            }.start()
        }
        return cached ?: ResolvedConn(login, "Twitch", null, null, null, null, url, 0L)
    }

    // MARK: - Real Sign-In Manager UI (Steam OpenID 2.0 + OAuth 2.0 PKCE)

    @JvmStatic
    fun openIntegrationsDialog(activity: Activity?, account: Int, dialogId: Long) {
        if (activity == null || activity.isFinishing) return
        val targetDid = if (dialogId > 0L) dialogId else UserConfig.getInstance(account).getClientUserId()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(8f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        }
        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Интеграции MediaTok (Вход)")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .create()

        fun rebuildRows() {
            root.removeAllViews()
            val cur = getIntegrations(targetDid)
            val bgColor = Theme.getColor(Theme.key_dialogBackground)
            val isDark = AndroidUtilities.computePerceivedBrightness(bgColor) < 0.5f

            for (p in IntegrationPlatform.values()) {
                val linkedVal = getField(cur, p)
                val isLinked = linkedVal.isNotBlank()

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
                    background = GradientDrawable().apply {
                        setColor(if (isDark) 0xFF1E1F22.toInt() else 0xFFF2F3F5.toInt())
                        cornerRadius = AndroidUtilities.dpf2(12f)
                    }
                }
                val icon = BrandIconView(activity).apply { setBrand(p.key, isDark) }
                row.addView(icon, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 10, 0))

                val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val titleTv = TextView(activity).apply {
                    text = p.title
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                    typeface = AndroidUtilities.bold()
                    setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                }
                val subTv = TextView(activity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    if (isLinked) {
                        text = "Подключено: ${linkedVal.substringBefore('|')}"
                        setTextColor(0xFF34C759.toInt())
                    } else {
                        text = if (p == IntegrationPlatform.STEAM) "Вход через Steam OpenID" else "Вход через OAuth 2.0"
                        setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                    }
                }
                col.addView(titleTv)
                col.addView(subTv)
                row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

                val btn = TextView(activity).apply {
                    text = if (isLinked) "Отвязать" else "Войти"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                    typeface = AndroidUtilities.bold()
                    setTextColor(if (isLinked) 0xFFFF453A.toInt() else 0xFF0A84FF.toInt())
                    setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
                    background = GradientDrawable().apply {
                        setColor(if (isLinked) 0x22FF453A else 0x220A84FF)
                        cornerRadius = AndroidUtilities.dpf2(14f)
                    }
                    setOnClickListener {
                        if (isLinked) {
                            val updated = getIntegrations(targetDid).copy()
                            setField(updated, p, "")
                            saveIntegrations(account, targetDid, updated, true)
                            rebuildRows()
                        } else {
                            startSignIn(activity, p, account, targetDid) {
                                rebuildRows()
                            }
                        }
                    }
                }
                row.addView(btn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0))
                root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f))
            }
        }

        rebuildRows()
        dialog.show()
    }

    private fun getField(d: PeerIntegrations, p: IntegrationPlatform): String = when (p) {
        IntegrationPlatform.STEAM -> d.steamInput
        IntegrationPlatform.SPOTIFY -> d.spotifyInput
        IntegrationPlatform.DISCORD -> d.discordTag
        IntegrationPlatform.YOUTUBE -> d.youtubeInput
        IntegrationPlatform.ROBLOX -> d.robloxInput
        IntegrationPlatform.TWITCH -> d.twitchInput
        IntegrationPlatform.GITHUB -> d.githubInput
    }

    private fun setField(d: PeerIntegrations, p: IntegrationPlatform, v: String) {
        when (p) {
            IntegrationPlatform.STEAM -> d.steamInput = v
            IntegrationPlatform.SPOTIFY -> d.spotifyInput = v
            IntegrationPlatform.DISCORD -> d.discordTag = v
            IntegrationPlatform.YOUTUBE -> d.youtubeInput = v
            IntegrationPlatform.ROBLOX -> d.robloxInput = v
            IntegrationPlatform.TWITCH -> d.twitchInput = v
            IntegrationPlatform.GITHUB -> d.githubInput = v
        }
    }

    private fun startSignIn(
        activity: Activity,
        platform: IntegrationPlatform,
        account: Int,
        dialogId: Long,
        onLinked: () -> Unit
    ) {
        if (platform == IntegrationPlatform.STEAM) {
            openSteamOpenIdDialog(activity, account, dialogId, onLinked)
            return
        }
        val savedCid = prefs().getString(PREFIX_CLIENT_ID + platform.key, "").orEmpty().trim()
        if (savedCid.isEmpty()) {
            copyToClipboard(activity, platform.redirectUri)
            val input = EditTextBoldCursor(activity).apply {
                hint = "Вставьте Client ID для ${platform.title}"
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
                setSingleLine(true)
            }
            val wrap = FrameLayout(activity).apply {
                setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(8f), AndroidUtilities.dp(20f), 0)
                addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
            }
            AlertDialog.Builder(activity)
                .setTitle("Вход через ${platform.title}")
                .setMessage("Для OAuth 2.0 PKCE нужен публичный Client ID из ${platform.title} Developer Portal.\n\nRedirect URI скопирован:\n${platform.redirectUri}")
                .setView(wrap)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Войти") { _, _ ->
                    val cid = input.text?.toString()?.trim().orEmpty()
                    if (cid.isNotEmpty()) {
                        prefs().edit().putString(PREFIX_CLIENT_ID + platform.key, cid).apply()
                        launchPkceBrowser(activity, platform, cid, account, dialogId)
                    }
                }
                .show()
        } else {
            launchPkceBrowser(activity, platform, savedCid, account, dialogId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openSteamOpenIdDialog(activity: Activity, account: Int, dialogId: Long, onLinked: () -> Unit) {
        val webView = WebView(activity)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        val dlg = AlertDialog.Builder(activity)
            .setTitle("Вход через Steam")
            .setView(webView)
            .setNegativeButton("Отмена", null)
            .create()

        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(500f))
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.host == "mediatok.local" && u.path == "/steam-openid") {
                    dlg.dismiss()
                    verifySteamOpenIdCallback(u, account, dialogId, onLinked)
                    return true
                }
                return false
            }
        }

        val authUri = Uri.parse("https://steamcommunity.com/openid/login").buildUpon()
            .appendQueryParameter("openid.ns", "http://specs.openid.net/auth/2.0")
            .appendQueryParameter("openid.mode", "checkid_setup")
            .appendQueryParameter("openid.return_to", "https://mediatok.local/steam-openid")
            .appendQueryParameter("openid.realm", "https://mediatok.local/")
            .appendQueryParameter("openid.identity", "http://specs.openid.net/auth/2.0/identifier_select")
            .appendQueryParameter("openid.claimed_id", "http://specs.openid.net/auth/2.0/identifier_select")
            .build().toString()
        webView.loadUrl(authUri)
        dlg.show()
    }

    private fun verifySteamOpenIdCallback(uri: Uri, account: Int, dialogId: Long, onLinked: () -> Unit) {
        val claimedId = uri.getQueryParameter("openid.claimed_id").orEmpty()
        val steamId64 = claimedId.substringAfterLast('/')
        if (steamId64.isEmpty() || !steamId64.all { it.isDigit() }) {
            toast("Steam не вернул идентификатор аккаунта")
            return
        }
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val form = StringBuilder()
                for (name in uri.queryParameterNames) {
                    if (name == "openid.mode") continue
                    val value = uri.getQueryParameter(name).orEmpty()
                    if (form.isNotEmpty()) form.append('&')
                    form.append(URLEncoder.encode(name, "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8"))
                }
                if (form.isNotEmpty()) form.append('&')
                form.append("openid.mode=check_authentication")

                conn = (URL("https://steamcommunity.com/openid/login").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(form.toString().toByteArray(Charsets.UTF_8)) }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (body.contains("is_valid:true")) {
                    val url = "https://steamcommunity.com/profiles/$steamId64"
                    AndroidUtilities.runOnUIThread {
                        val cur = getIntegrations(dialogId).copy()
                        cur.steamInput = url
                        saveIntegrations(account, dialogId, cur, true)
                        toast("Steam подключён!")
                        onLinked()
                    }
                } else {
                    toast("Подпись Steam не прошла проверку")
                }
            } catch (e: Throwable) {
                FileLog.e(e)
                toast("Ошибка проверки Steam: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun launchPkceBrowser(
        activity: Activity,
        platform: IntegrationPlatform,
        clientId: String,
        account: Int,
        dialogId: Long
    ) {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val state = UUID.randomUUID().toString()

        pendingPkce = PendingPkce(platform, clientId, verifier, state, account, dialogId)

        val authUri = Uri.parse(platform.authorizeEndpoint).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", platform.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", platform.scope)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, authUri))
        } catch (_: Throwable) {
            toast("Не удалось открыть браузер для входа в ${platform.title}")
        }
    }

    @JvmStatic
    fun handleOAuthCallback(uri: Uri?): Boolean {
        if (uri == null || !uri.scheme.equals("mediatok", ignoreCase = true) || !uri.host.equals("oauth", ignoreCase = true)) {
            return false
        }
        val pending = pendingPkce ?: return true
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        if (state != pending.state || code.isNullOrBlank()) {
            pendingPkce = null
            toast("Авторизация ${pending.platform.title} отменена")
            return true
        }
        pendingPkce = null

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val form = listOf(
                    "grant_type" to "authorization_code",
                    "client_id" to pending.clientId,
                    "code" to code,
                    "redirect_uri" to pending.platform.redirectUri,
                    "code_verifier" to pending.verifier
                ).joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

                conn = (URL(pending.platform.tokenEndpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val tokenJson = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val accessToken = tokenJson.optString("access_token", "")
                if (accessToken.isBlank()) {
                    toast("Не удалось получить токен ${pending.platform.title}")
                    return@Thread
                }
                val identity = fetchVerifiedProfile(pending.platform, pending.clientId, accessToken)
                if (!identity.isNullOrBlank()) {
                    AndroidUtilities.runOnUIThread {
                        val cur = getIntegrations(pending.dialogId).copy()
                        setField(cur, pending.platform, identity)
                        saveIntegrations(pending.account, pending.dialogId, cur, true)
                        toast("${pending.platform.title} подключён!")
                    }
                }
            } catch (e: Throwable) {
                FileLog.e(e)
                toast("Ошибка OAuth ${pending.platform.title}: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }.start()
        return true
    }

    private fun fetchVerifiedProfile(platform: IntegrationPlatform, clientId: String, token: String): String? {
        val url = when (platform) {
            IntegrationPlatform.SPOTIFY -> "https://api.spotify.com/v1/me"
            IntegrationPlatform.DISCORD -> "https://discord.com/api/users/@me"
            IntegrationPlatform.YOUTUBE -> "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true"
            IntegrationPlatform.ROBLOX -> "https://apis.roblox.com/oauth/v1/userinfo"
            IntegrationPlatform.TWITCH -> "https://api.twitch.tv/helix/users"
            IntegrationPlatform.GITHUB -> "https://api.github.com/user"
            IntegrationPlatform.STEAM -> return null
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "MediaTok/OAuth")
                if (platform == IntegrationPlatform.TWITCH) setRequestProperty("Client-Id", clientId)
            }
            val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            when (platform) {
                IntegrationPlatform.SPOTIFY -> {
                    val display = obj.optString("display_name", "").ifEmpty { obj.optString("id", "") }
                    val ext = obj.optJSONObject("external_urls")?.optString("spotify", "").orEmpty()
                    if (ext.isNotEmpty() && display.isNotEmpty()) "$display|$ext" else display
                }
                IntegrationPlatform.DISCORD -> obj.optString("username", "").ifEmpty { obj.optString("global_name", "") }
                IntegrationPlatform.YOUTUBE -> {
                    val first = obj.optJSONArray("items")?.optJSONObject(0)
                    val custom = first?.optJSONObject("snippet")?.optString("customUrl", "").orEmpty()
                    val cid = first?.optString("id", "").orEmpty()
                    if (custom.isNotEmpty()) custom else if (cid.isNotEmpty()) "https://www.youtube.com/channel/$cid" else null
                }
                IntegrationPlatform.ROBLOX -> {
                    val sub = obj.optString("sub", "")
                    if (sub.isNotEmpty()) "https://www.roblox.com/users/$sub/profile" else obj.optString("preferred_username", "")
                }
                IntegrationPlatform.TWITCH -> obj.optJSONArray("data")?.optJSONObject(0)?.optString("login", "")
                IntegrationPlatform.GITHUB -> obj.optString("login", "")
                IntegrationPlatform.STEAM -> null
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("MediaTok", text))
        toast("Скопировано: ${text.take(40)}")
    }

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            Toast.makeText(ApplicationLoader.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
