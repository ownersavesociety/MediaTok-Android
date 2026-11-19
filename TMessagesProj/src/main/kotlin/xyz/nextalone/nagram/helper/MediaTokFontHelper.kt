package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import tw.nekomimi.nekogram.helpers.AppRestartHelper
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object MediaTokFontHelper {

    private const val PREFS_NAME = "mediatok_fonts"
    private const val KEY_UI_FONT = "ui_font_id"
    private const val KEY_CHAT_STYLE = "chat_text_style"
    private const val PREFIX_BUNDLED = "bundled:"
    private const val PREFIX_CUSTOM = "custom:"
    const val REQUEST_CODE_IMPORT_FONT = 74913

    data class BundledFont(
        val id: String,
        val name: String,
        val category: String,
        val assetPath: String,
        val isMono: Boolean = false
    )

    data class CustomFontEntry(
        val fileName: String,
        val displayName: String,
        val file: File
    )

    data class ChatTextStyle(
        val id: String,
        val name: String,
        val category: String,
        val cyrillicFull: Boolean
    )

    val BUNDLED_FONTS: List<BundledFont> = listOf(
        // Современные (16)
        BundledFont("inter", "Inter", "Современные", "fonts/mediatok/mt_inter.ttf"),
        BundledFont("montserrat", "Montserrat", "Современные", "fonts/mediatok/mt_montserrat.ttf"),
        BundledFont("manrope", "Manrope", "Современные", "fonts/mediatok/mt_manrope.ttf"),
        BundledFont("nunito", "Nunito", "Современные", "fonts/mediatok/mt_nunito.ttf"),
        BundledFont("comfortaa", "Comfortaa", "Современные", "fonts/mediatok/mt_comfortaa.ttf"),
        BundledFont("rubik", "Rubik", "Современные", "fonts/mediatok/mt_rubik.ttf"),
        BundledFont("onest", "Onest", "Современные", "fonts/mediatok/mt_onest.ttf"),
        BundledFont("golos_text", "Golos Text", "Современные", "fonts/mediatok/mt_golos_text.ttf"),
        BundledFont("raleway", "Raleway", "Современные", "fonts/mediatok/mt_raleway.ttf"),
        BundledFont("mulish", "Mulish", "Современные", "fonts/mediatok/mt_mulish.ttf"),
        BundledFont("jost", "Jost", "Современные", "fonts/mediatok/mt_jost.ttf"),
        BundledFont("ubuntu", "Ubuntu", "Современные", "fonts/mediatok/mt_ubuntu.ttf"),
        BundledFont("pt_sans", "PT Sans", "Современные", "fonts/mediatok/mt_pt_sans.ttf"),
        BundledFont("fira_sans", "Fira Sans", "Современные", "fonts/mediatok/mt_fira_sans.ttf"),
        BundledFont("exo_2", "Exo 2", "Современные", "fonts/mediatok/mt_exo_2.ttf"),
        BundledFont("oswald", "Oswald", "Современные", "fonts/mediatok/mt_oswald.ttf"),

        // Кибер / Пиксель / Арт (13)
        BundledFont("unbounded", "Unbounded", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_unbounded.ttf"),
        BundledFont("russo_one", "Russo One", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_russo_one.ttf"),
        BundledFont("tektur", "Tektur", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_tektur.ttf"),
        BundledFont("jura", "Jura", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_jura.ttf"),
        BundledFont("press_start_2p", "Press Start 2P", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_press_start_2p.ttf"),
        BundledFont("pixelify_sans", "Pixelify Sans", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_pixelify_sans.ttf"),
        BundledFont("tiny5", "Tiny5", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_tiny5.ttf"),
        BundledFont("rubik_glitch", "Rubik Glitch", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_rubik_glitch.ttf"),
        BundledFont("rubik_bubbles", "Rubik Bubbles", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_rubik_bubbles.ttf"),
        BundledFont("rubik_mono_one", "Rubik Mono One", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_rubik_mono_one.ttf"),
        BundledFont("kelly_slab", "Kelly Slab", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_kelly_slab.ttf"),
        BundledFont("underdog", "Underdog", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_underdog.ttf"),
        BundledFont("poiret_one", "Poiret One", "Кибер / Пиксель / Арт", "fonts/mediatok/mt_poiret_one.ttf"),

        // С засечками (6)
        BundledFont("playfair_display", "Playfair Display", "С засечками (Serif)", "fonts/mediatok/mt_playfair_display.ttf"),
        BundledFont("cormorant_garamond", "Cormorant Garamond", "С засечками (Serif)", "fonts/mediatok/mt_cormorant_garamond.ttf"),
        BundledFont("lora", "Lora", "С засечками (Serif)", "fonts/mediatok/mt_lora.ttf"),
        BundledFont("yeseva_one", "Yeseva One", "С засечками (Serif)", "fonts/mediatok/mt_yeseva_one.ttf"),
        BundledFont("philosopher", "Philosopher", "С засечками (Serif)", "fonts/mediatok/mt_philosopher.ttf"),
        BundledFont("alice", "Alice", "С засечками (Serif)", "fonts/mediatok/mt_alice.ttf"),

        // Рукописные и Фан (9)
        BundledFont("caveat", "Caveat", "Рукописные и Фан", "fonts/mediatok/mt_caveat.ttf"),
        BundledFont("pacifico", "Pacifico", "Рукописные и Фан", "fonts/mediatok/mt_pacifico.ttf"),
        BundledFont("marck_script", "Marck Script", "Рукописные и Фан", "fonts/mediatok/mt_marck_script.ttf"),
        BundledFont("bad_script", "Bad Script", "Рукописные и Фан", "fonts/mediatok/mt_bad_script.ttf"),
        BundledFont("lobster", "Lobster", "Рукописные и Фан", "fonts/mediatok/mt_lobster.ttf"),
        BundledFont("amatic_sc", "Amatic SC", "Рукописные и Фан", "fonts/mediatok/mt_amatic_sc.ttf"),
        BundledFont("neucha", "Neucha", "Рукописные и Фан", "fonts/mediatok/mt_neucha.ttf"),
        BundledFont("balsamiq_sans", "Balsamiq Sans", "Рукописные и Фан", "fonts/mediatok/mt_balsamiq_sans.ttf"),
        BundledFont("shantell_sans", "Shantell Sans", "Рукописные и Фан", "fonts/mediatok/mt_shantell_sans.ttf"),

        // Моноширинные (4)
        BundledFont("jetbrains_mono", "JetBrains Mono", "Моноширинные", "fonts/mediatok/mt_jetbrains_mono.ttf", isMono = true),
        BundledFont("fira_code", "Fira Code", "Моноширинные", "fonts/mediatok/mt_fira_code.ttf", isMono = true),
        BundledFont("ibm_plex_mono", "IBM Plex Mono", "Моноширинные", "fonts/mediatok/mt_ibm_plex_mono.ttf", isMono = true),
        BundledFont("anonymous_pro", "Anonymous Pro", "Моноширинные", "fonts/mediatok/mt_anonymous_pro.ttf", isMono = true)
    )

    val CHAT_TEXT_STYLES: List<ChatTextStyle> = listOf(
        // Русский + English (20)
        ChatTextStyle("smallcaps", "Маленькие капсы", "Кириллица + Латиница", true),
        ChatTextStyle("wide", "Широкий Vaporwave", "Кириллица + Латиница", true),
        ChatTextStyle("spaced", "Разрядка (А Э С Т Е Т И К А)", "Кириллица + Латиница", true),
        ChatTextStyle("ancient", "Старославянский", "Кириллица + Латиница", true),
        ChatTextStyle("cyber", "Киберпанк", "Кириллица + Латиница", true),
        ChatTextStyle("runes", "Рунический / Греч.", "Кириллица + Латиница", true),
        ChatTextStyle("bubble", "В кружочках", "Кириллица + Латиница", true),
        ChatTextStyle("square", "В квадратах", "Кириллица + Латиница", true),
        ChatTextStyle("underline", "Подчёркнутый", "Кириллица + Латиница", true),
        ChatTextStyle("double_line", "Двойная линия", "Кириллица + Латиница", true),
        ChatTextStyle("strike", "Зачёркнутый", "Кириллица + Латиница", true),
        ChatTextStyle("slash", "Перечёркнутый", "Кириллица + Латиница", true),
        ChatTextStyle("overline", "Надчёркнутый", "Кириллица + Латиница", true),
        ChatTextStyle("wave", "Волнистый", "Кириллица + Латиница", true),
        ChatTextStyle("glitch", "Глитч / Zalgo", "Кириллица + Латиница", true),
        ChatTextStyle("stars", "Эстетик ✦", "Кириллица + Латиница", true),
        ChatTextStyle("brackets", "Японские рамки 『 』", "Кириллица + Латиница", true),
        ChatTextStyle("leet", "Хакер 1337", "Кириллица + Латиница", true),
        ChatTextStyle("fence", "Заборчик", "Кириллица + Латиница", true),
        ChatTextStyle("upside", "Перевёрнутый", "Кириллица + Латиница", true),
        ChatTextStyle("mirror", "Зеркальный", "Кириллица + Латиница", true),

        // Латиница + Цифры Unicode Math (11)
        ChatTextStyle("bold", "Bold Serif", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("italic", "Italic Serif", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("bold_italic", "Bold Italic", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("sans_bold", "Sans Bold", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("sans_italic", "Sans Italic", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("script", "Calligraphy Script", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("bold_script", "Bold Script", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("fraktur", "Gothic / Fraktur", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("bold_fraktur", "Bold Gothic", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("double_struck", "Double-Struck", "Латиница + Цифры (Unicode)", false),
        ChatTextStyle("mono", "Monospace Unicode", "Латиница + Цифры (Unicode)", false)
    )

    private val previewCache = ConcurrentHashMap<String, Typeface>()

    @Volatile private var initialized = false
    @Volatile private var activeUiFontId: String = ""
    @Volatile private var activeChatStyleId: String = ""
    @Volatile private var activeIsMono: Boolean = false
    @Volatile private var activeRegular: Typeface? = null
    @Volatile private var activeBold: Typeface? = null
    @Volatile private var activeItalic: Typeface? = null
    @Volatile private var activeBoldItalic: Typeface? = null

    private var defaultSystemTypeface: Typeface? = null
    private var defaultSystemBoldTypeface: Typeface? = null

    private fun prefs(): SharedPreferences? {
        val ctx = ApplicationLoader.applicationContext ?: return null
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun customFontsDir(): File? {
        val ctx = ApplicationLoader.applicationContext ?: return null
        val dir = File(ctx.filesDir, "mediatok_fonts")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val p = prefs() ?: return
            if (defaultSystemTypeface == null) {
                defaultSystemTypeface = Typeface.SANS_SERIF
                defaultSystemBoldTypeface = Typeface.DEFAULT_BOLD
            }
            activeUiFontId = p.getString(KEY_UI_FONT, "")?.trim().orEmpty()
            activeChatStyleId = p.getString(KEY_CHAT_STYLE, "")?.trim().orEmpty()
            loadActiveTypefaceLocked()
            initialized = true
        }
    }

    private fun loadActiveTypefaceLocked() {
        val id = activeUiFontId
        if (id.isEmpty()) {
            activeRegular = null
            activeBold = null
            activeItalic = null
            activeBoldItalic = null
            activeIsMono = false
            return
        }
        val base = loadTypefaceById(id)
        if (base == null) {
            activeRegular = null
            activeBold = null
            activeItalic = null
            activeBoldItalic = null
            activeIsMono = false
            return
        }
        activeIsMono = id.startsWith(PREFIX_BUNDLED) &&
            BUNDLED_FONTS.firstOrNull { it.id == id.removePrefix(PREFIX_BUNDLED) }?.isMono == true
        activeRegular = base
        activeBold = createStyled(base, Typeface.BOLD, 650, false)
        activeItalic = createStyled(base, Typeface.ITALIC, 400, true)
        activeBoldItalic = createStyled(base, Typeface.BOLD_ITALIC, 650, true)
    }

    private fun createStyled(base: Typeface, style: Int, weight: Int, italic: Boolean): Typeface {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, weight, italic)
            } else {
                Typeface.create(base, style)
            }
        } catch (_: Throwable) {
            base
        }
    }

    private fun loadTypefaceById(id: String): Typeface? {
        val cached = previewCache[id]
        if (cached != null) return cached
        val ctx = ApplicationLoader.applicationContext ?: return null
        val tf = try {
            when {
                id.startsWith(PREFIX_BUNDLED) -> {
                    val slug = id.removePrefix(PREFIX_BUNDLED)
                    val entry = BUNDLED_FONTS.firstOrNull { it.id == slug } ?: return null
                    Typeface.createFromAsset(ctx.assets, entry.assetPath)
                }
                id.startsWith(PREFIX_CUSTOM) -> {
                    val fileName = id.removePrefix(PREFIX_CUSTOM)
                    val dir = customFontsDir() ?: return null
                    val file = File(dir, fileName)
                    if (!file.exists() || !file.isFile) return null
                    Typeface.createFromFile(file)
                }
                else -> null
            }
        } catch (e: Throwable) {
            FileLog.e("MediaTokFontHelper loadTypefaceById($id) failed", e)
            null
        }
        if (tf != null) {
            previewCache[id] = tf
        }
        return tf
    }

    @JvmStatic
    fun hasCustomUiFont(): Boolean {
        ensureInitialized()
        return activeRegular != null
    }

    @JvmStatic
    fun getRegularTypeface(): Typeface? {
        ensureInitialized()
        return activeRegular
    }

    @JvmStatic
    fun getBoldTypeface(): Typeface? {
        ensureInitialized()
        return activeBold ?: activeRegular
    }

    @JvmStatic
    fun getTypefaceForAsset(assetPath: String?): Typeface? {
        ensureInitialized()
        val regular = activeRegular ?: return null
        val path = assetPath.orEmpty().lowercase(Locale.ROOT)
        if (path.contains("mono") && !activeIsMono) {
            return null
        }
        val isBold = path.contains("medium") || path.contains("bold")
        val isItalic = path.contains("italic")
        return when {
            isBold && isItalic -> activeBoldItalic ?: activeBold ?: regular
            isBold -> activeBold ?: regular
            isItalic -> activeItalic ?: regular
            else -> regular
        }
    }

    @JvmStatic
    fun applyGlobalSystemTypeface() {
        ensureInitialized()
        val reg = activeRegular ?: defaultSystemTypeface ?: return
        val bld = activeBold ?: defaultSystemBoldTypeface ?: reg
        setStaticTypefaceField("DEFAULT", reg)
        setStaticTypefaceField("SANS_SERIF", reg)
        setStaticTypefaceField("DEFAULT_BOLD", bld)
        try {
            val mapField = Typeface::class.java.getDeclaredField("sSystemFontMap")
            mapField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val existing = mapField.get(null) as? Map<String, Typeface>
            if (existing != null) {
                val updated = HashMap(existing)
                updated["sans-serif"] = reg
                updated["sans-serif-medium"] = bld
                updated["sans-serif-light"] = reg
                updated["sans-serif-condensed"] = reg
                mapField.set(null, updated)
            }
        } catch (_: Throwable) {
            // Android 12+ hidden API restrictions are handled by explicit hooks in AndroidUtilities, Theme, SimpleTextView, etc.
        }
    }

    private fun setStaticTypefaceField(fieldName: String, typeface: Typeface) {
        try {
            val field: Field = Typeface::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, typeface)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun setUiFontId(id: String) {
        ensureInitialized()
        synchronized(this) {
            activeUiFontId = id.trim()
            prefs()?.edit()?.putString(KEY_UI_FONT, activeUiFontId)?.apply()
            loadActiveTypefaceLocked()
        }
        AndroidUtilities.clearTypefaceCache()
        applyGlobalSystemTypeface()
        try {
            if (Theme.chat_msgTextPaint != null) {
                Theme.createCommonMessageResources()
            }
            val ctx = ApplicationLoader.applicationContext
            if (ctx != null && Theme.dialogs_namePaint != null) {
                Theme.dialogs_namePaint = null
                Theme.createDialogsResources(ctx)
            }
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun getSelectedUiFontId(): String {
        ensureInitialized()
        return activeUiFontId
    }

    @JvmStatic
    fun getSelectedUiFontDisplayName(): String {
        ensureInitialized()
        val id = activeUiFontId
        if (id.isEmpty()) return "Системный"
        if (id.startsWith(PREFIX_BUNDLED)) {
            val slug = id.removePrefix(PREFIX_BUNDLED)
            return BUNDLED_FONTS.firstOrNull { it.id == slug }?.name ?: slug
        }
        if (id.startsWith(PREFIX_CUSTOM)) {
            return cleanCustomFontName(id.removePrefix(PREFIX_CUSTOM))
        }
        return "Системный"
    }

    @JvmStatic
    fun getActiveChatStyleId(): String {
        ensureInitialized()
        return activeChatStyleId
    }

    @JvmStatic
    fun setActiveChatStyleId(styleId: String) {
        ensureInitialized()
        val clean = styleId.trim().lowercase(Locale.ROOT)
        activeChatStyleId = if (CHAT_TEXT_STYLES.any { it.id == clean }) clean else ""
        prefs()?.edit()?.putString(KEY_CHAT_STYLE, activeChatStyleId)?.apply()
    }

    @JvmStatic
    fun getActiveChatStyleDisplayName(): String {
        ensureInitialized()
        if (activeChatStyleId.isEmpty()) return "Выкл"
        return CHAT_TEXT_STYLES.firstOrNull { it.id == activeChatStyleId }?.name ?: "Выкл"
    }

    fun listCustomFonts(): List<CustomFontEntry> {
        val dir = customFontsDir() ?: return emptyList()
        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true))
        } ?: return emptyList()
        return files.sortedBy { it.name.lowercase(Locale.ROOT) }.map {
            CustomFontEntry(it.name, cleanCustomFontName(it.name), it)
        }
    }

    private fun cleanCustomFontName(fileName: String): String {
        return fileName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifEmpty { fileName }
    }

    fun startImportFontPicker(activity: Activity?) {
        val act = activity ?: getActivity() ?: return
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype", "application/octet-stream"))
            }
            act.startActivityForResult(intent, REQUEST_CODE_IMPORT_FONT)
        } catch (e: Throwable) {
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                act.startActivityForResult(fallback, REQUEST_CODE_IMPORT_FONT)
            } catch (e2: Throwable) {
                FileLog.e(e2)
                toast("Не удалось открыть выбор файла шрифта")
            }
        }
    }

    @JvmStatic
    fun handleActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE_IMPORT_FONT) return false
        if (resultCode != Activity.RESULT_OK || data?.data == null) return true
        val uri = data.data ?: return true
        importFontFromUri(activity, uri)
        return true
    }

    private fun importFontFromUri(activity: Activity?, uri: Uri) {
        val ctx = activity ?: ApplicationLoader.applicationContext ?: return
        val dir = customFontsDir() ?: return
        try {
            val rawName = resolveDisplayName(ctx, uri) ?: "custom_${System.currentTimeMillis()}.ttf"
            val ext = when {
                rawName.endsWith(".otf", true) -> ".otf"
                else -> ".ttf"
            }
            val safeBase = rawName.substringBeforeLast('.')
                .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ_\\- ]"), "")
                .trim()
                .replace(' ', '_')
                .take(40)
                .ifEmpty { "font_${System.currentTimeMillis()}" }
            val destFile = File(dir, safeBase + ext)
            val tmpFile = File(dir, ".tmp_${System.currentTimeMillis()}$ext")

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > 25L * 1024L * 1024L) {
                            tmpFile.delete()
                            toast("Файл слишком большой (макс. 25 МБ)")
                            return
                        }
                        output.write(buf, 0, read)
                    }
                }
            } ?: run {
                toast("Не удалось прочитать файл")
                return
            }

            if (!isValidFontHeader(tmpFile)) {
                tmpFile.delete()
                toast("Это не .ttf / .otf шрифт")
                return
            }

            val loaded = try {
                Typeface.createFromFile(tmpFile)
            } catch (_: Throwable) {
                null
            }
            if (loaded == null) {
                tmpFile.delete()
                toast("Файл шрифта повреждён или не поддерживается")
                return
            }

            if (destFile.exists()) destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }

            val fontId = PREFIX_CUSTOM + destFile.name
            previewCache[fontId] = loaded
            setUiFontId(fontId)
            toast("Шрифт «${cleanCustomFontName(destFile.name)}» загружен и применён")
            promptRestartIfNeeded(activity)
        } catch (e: Throwable) {
            FileLog.e(e)
            toast("Ошибка импорта шрифта: ${e.message}")
        }
    }

    private fun isValidFontHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            file.inputStream().use { s ->
                val b = ByteArray(4)
                if (s.read(b) != 4) return false
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                val b2 = b[2].toInt() and 0xFF
                val b3 = b[3].toInt() and 0xFF
                val isTtf = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
                val isOtf = b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code
                val isTrue = b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code
                val isTtc = b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code
                isTtf || isOtf || isTrue || isTtc
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveDisplayName(ctx: Context, uri: Uri): String? {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        } catch (_: Throwable) {
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    // ===================== OUTGOING CHAT TEXT TRANSFORMATION =====================

    @JvmStatic
    fun handleCommand(
        account: Int,
        dialogId: Long,
        cmdLower: String,
        arg: String,
        params: SendMessagesHelper.SendMessageParams
    ): Boolean {
        ensureInitialized()
        when (cmdLower) {
            ".font", ".fonts" -> {
                val clean = arg.trim()
                if (clean.isEmpty()) {
                    AndroidUtilities.runOnUIThread {
                        openFontStudioDialog(getActivity(), 0)
                    }
                    return true
                }
                val lower = clean.lowercase(Locale.ROOT)
                if (lower == "off" || lower == "0" || lower == "выкл" || lower == "сброс" || lower == "reset") {
                    setActiveChatStyleId("")
                    toast("MediaTok: шрифт сообщений ВЫКЛ")
                    return true
                }
                if (lower == "list" || lower == "стили" || lower == "список") {
                    AndroidUtilities.runOnUIThread {
                        openFontStudioDialog(getActivity(), 1)
                    }
                    return true
                }
                val style = findChatStyle(lower)
                if (style != null) {
                    setActiveChatStyleId(style.id)
                    toast("MediaTok: авто-шрифт «${style.name}» ВКЛ (${transformText("Привет / Hello", style.id)})")
                } else {
                    val bundled = BUNDLED_FONTS.firstOrNull {
                        it.id.equals(lower, true) || it.name.equals(clean, true) || it.id.startsWith(lower)
                    }
                    if (bundled != null) {
                        setUiFontId(PREFIX_BUNDLED + bundled.id)
                        toast("MediaTok: шрифт клиента «${bundled.name}» применён")
                        AndroidUtilities.runOnUIThread { promptRestartIfNeeded(getActivity()) }
                    } else {
                        toast("Не найден стиль «$clean». Введите .font для списка")
                    }
                }
                return true
            }
            ".f" -> {
                val parts = arg.trim().split(Regex("\\s+"), limit = 2)
                val styleQuery = parts.getOrNull(0)?.trim().orEmpty().lowercase(Locale.ROOT)
                val textBody = parts.getOrNull(1)?.trim().orEmpty()
                if (styleQuery.isEmpty()) {
                    AndroidUtilities.runOnUIThread { openFontStudioDialog(getActivity(), 1) }
                    return true
                }
                if (textBody.isEmpty()) {
                    toast("Формат: .f <стиль> <текст> (напр. .f cyber Привет!)")
                    return true
                }
                val style = findChatStyle(styleQuery)
                if (style == null) {
                    toast("Стиль «$styleQuery» не найден. Введите .font list")
                    return true
                }
                val styled = transformSmart(textBody, style.id)
                val safeText = if (styled.startsWith(".")) " $styled" else styled
                val p = SendMessagesHelper.SendMessageParams.of(safeText, dialogId)
                p.replyToMsg = params.replyToMsg
                p.replyToTopMsg = params.replyToTopMsg
                val extras = HashMap<String, String>()
                extras["mt_styled"] = "1"
                p.params = extras
                SendMessagesHelper.getInstance(account).sendMessage(p)
                return true
            }
            else -> return false
        }
    }

    private fun findChatStyle(query: String): ChatTextStyle? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return null
        CHAT_TEXT_STYLES.firstOrNull { it.id == q }?.let { return it }
        CHAT_TEXT_STYLES.firstOrNull { it.id.startsWith(q) || it.name.lowercase(Locale.ROOT).startsWith(q) }?.let { return it }
        return CHAT_TEXT_STYLES.firstOrNull { it.id.contains(q) || it.name.lowercase(Locale.ROOT).contains(q) }
    }

    @JvmStatic
    fun transformOutgoingParams(params: SendMessagesHelper.SendMessageParams?) {
        if (params == null) return
        val extraParams = params.params
        if (extraParams != null && "1" == extraParams["mt_styled"]) return
        ensureInitialized()
        val styleId = activeChatStyleId
        if (styleId.isEmpty()) return
        if (params.retryMessageObject != null) return

        val ents = params.entities
        val msg = params.message
        if (!msg.isNullOrBlank()) {
            val trimmed = msg.trimStart()
            if (!trimmed.startsWith(".") && !trimmed.startsWith("/")) {
                if (ents == null || ents.isEmpty()) {
                    params.message = transformSmart(msg, styleId)
                } else {
                    params.message = transformWithEntityRemap(msg, styleId, ents)
                }
            }
        }

        val cap = params.caption
        if (!cap.isNullOrBlank()) {
            val trimmed = cap.trimStart()
            if (!trimmed.startsWith(".") && !trimmed.startsWith("/")) {
                if (ents == null || ents.isEmpty()) {
                    params.caption = transformSmart(cap, styleId)
                } else {
                    params.caption = transformWithEntityRemap(cap, styleId, ents)
                }
            }
        }
    }

    private fun transformWithEntityRemap(
        text: String,
        styleId: String,
        entities: ArrayList<TLRPC.MessageEntity>
    ): String {
        if (styleId == "mirror" || styleId == "upside") {
            return text
        }
        val protectedMask = BooleanArray(text.length)
        for (e in entities) {
            val keepRaw = e is TLRPC.TL_messageEntityCustomEmoji ||
                e is TLRPC.TL_messageEntityUrl ||
                e is TLRPC.TL_messageEntityMention ||
                e is TLRPC.TL_messageEntityMentionName ||
                e is TLRPC.TL_messageEntityBotCommand ||
                e is TLRPC.TL_messageEntityHashtag ||
                e is TLRPC.TL_messageEntityEmail ||
                e is TLRPC.TL_messageEntityCode ||
                e is TLRPC.TL_messageEntityPre
            if (keepRaw) {
                val start = e.offset.coerceIn(0, text.length)
                val end = (e.offset + e.length).coerceIn(start, text.length)
                for (i in start until end) protectedMask[i] = true
            }
        }

        val out = StringBuilder(text.length * 2)
        val indexMap = IntArray(text.length + 1)
        var i = 0
        var fenceIdx = 0
        while (i < text.length) {
            indexMap[i] = out.length
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < text.length) {
                out.append(ch)
                out.append(text[i + 1])
                indexMap[i + 1] = out.length
                i += 2
                continue
            }
            if (protectedMask[i]) {
                out.append(ch)
                i++
                continue
            }
            val next = transformSingleChar(ch, styleId, fenceIdx)
            if (ch.isLetter()) fenceIdx++
            out.append(next)
            i++
        }
        indexMap[text.length] = out.length

        for (e in entities) {
            val oldStart = e.offset.coerceIn(0, text.length)
            val oldEnd = (e.offset + e.length).coerceIn(oldStart, text.length)
            e.offset = indexMap[oldStart]
            e.length = (indexMap[oldEnd] - indexMap[oldStart]).coerceAtLeast(0)
        }
        return out.toString()
    }

    fun transformSmart(text: String, styleId: String): String {
        if (styleId.isEmpty() || text.isEmpty()) return text
        if (styleId == "stars") {
            return "✦ ${transformTokens(text, "smallcaps")} ✦"
        }
        if (styleId == "brackets") {
            return "『${text}』"
        }
        if (styleId == "mirror") {
            return if (containsSpecialToken(text)) transformTokens(text, styleId) else reverseCodePoints(text)
        }
        if (styleId == "upside") {
            return if (containsSpecialToken(text)) transformTokens(text, styleId) else flipUpsideDown(text)
        }
        return transformTokens(text, styleId)
    }

    fun transformText(text: String, styleId: String): String = transformSmart(text, styleId)

    private fun containsSpecialToken(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("http://") || lower.contains("https://") || lower.contains("t.me/") ||
            text.contains("@") || text.contains("#")
    }

    private fun isProtectedToken(token: String): Boolean {
        if (token.isEmpty()) return true
        val lower = token.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("t.me/") || lower.startsWith("www.")) return true
        if (token.startsWith("@") && token.length > 1) return true
        if (token.startsWith("#") && token.length > 1) return true
        if (token.startsWith("/") && token.length > 1) return true
        return false
    }

    private fun transformTokens(text: String, styleId: String): String {
        val out = StringBuilder(text.length * 2)
        var i = 0
        var fenceIdx = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                out.append(text[i])
                i++
                continue
            }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) {
                end++
            }
            val token = text.substring(i, end)
            if (isProtectedToken(token)) {
                out.append(token)
            } else if (styleId == "mirror") {
                out.append(reverseCodePoints(token))
            } else if (styleId == "upside") {
                out.append(flipUpsideDown(token))
            } else {
                var k = 0
                while (k < token.length) {
                    val ch = token[k]
                    if (Character.isHighSurrogate(ch) && k + 1 < token.length) {
                        out.append(ch).append(token[k + 1])
                        k += 2
                        continue
                    }
                    out.append(transformSingleChar(ch, styleId, fenceIdx))
                    if (ch.isLetter()) fenceIdx++
                    k++
                }
            }
            i = end
        }
        return out.toString()
    }

    private fun reverseCodePoints(s: String): String {
        val cps = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            cps.add(cp)
            i += Character.charCount(cp)
        }
        val sb = StringBuilder(s.length)
        for (j in cps.size - 1 downTo 0) {
            sb.appendCodePoint(cps[j])
        }
        return sb.toString()
    }

    private fun flipUpsideDown(s: String): String {
        val cps = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            cps.add(cp)
            i += Character.charCount(cp)
        }
        val sb = StringBuilder(s.length)
        for (j in cps.size - 1 downTo 0) {
            val cp = cps[j]
            if (cp <= 0xFFFF) {
                val mapped = UPSIDE_MAP[cp.toChar()]
                if (mapped != null) {
                    sb.append(mapped)
                    continue
                }
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun transformSingleChar(ch: Char, styleId: String, index: Int): String {
        return when (styleId) {
            "smallcaps" -> SMALLCAPS_MAP[ch] ?: ch.toString()
            "wide" -> when {
                ch in '!'..'~' -> (ch.code + 0xFEE0).toChar().toString()
                isCyrillicLetter(ch) -> "$ch\u2009"
                else -> ch.toString()
            }
            "spaced" -> if (ch.isLetterOrDigit()) "$ch " else ch.toString()
            "ancient" -> ANCIENT_MAP[ch] ?: ch.toString()
            "cyber" -> CYBER_MAP[ch] ?: ch.toString()
            "runes" -> RUNES_MAP[ch] ?: ch.toString()
            "bubble" -> when {
                ch in 'A'..'Z' -> (0x24B6 + (ch - 'A')).toChar().toString()
                ch in 'a'..'z' -> (0x24D0 + (ch - 'a')).toChar().toString()
                ch == '0' -> "\u24EA"
                ch in '1'..'9' -> (0x2460 + (ch - '1')).toChar().toString()
                ch.isLetterOrDigit() -> "$ch\u20DD"
                else -> ch.toString()
            }
            "square" -> when {
                ch in 'A'..'Z' -> String(Character.toChars(0x1F130 + (ch - 'A')))
                ch in 'a'..'z' -> String(Character.toChars(0x1F130 + (ch - 'a')))
                ch.isLetterOrDigit() -> "$ch\u20DE"
                else -> ch.toString()
            }
            "underline" -> if (!ch.isWhitespace()) "$ch\u0332" else ch.toString()
            "double_line" -> if (!ch.isWhitespace()) "$ch\u0333" else ch.toString()
            "strike" -> if (!ch.isWhitespace()) "$ch\u0336" else ch.toString()
            "slash" -> if (!ch.isWhitespace()) "$ch\u0337" else ch.toString()
            "overline" -> if (!ch.isWhitespace()) "$ch\u0305" else ch.toString()
            "wave" -> if (!ch.isWhitespace()) "$ch\u0330" else ch.toString()
            "glitch" -> if (ch.isLetterOrDigit()) {
                val m1 = GLITCH_MID[index % GLITCH_MID.size]
                val m2 = GLITCH_UP[(index * 3 + ch.code) % GLITCH_UP.size]
                "$ch$m1$m2"
            } else ch.toString()
            "leet" -> LEET_MAP[ch] ?: ch.toString()
            "fence" -> if (ch.isLetter()) {
                if (index % 2 == 0) ch.uppercaseChar().toString() else ch.lowercaseChar().toString()
            } else ch.toString()
            "bold" -> mapMath(ch, 0x1D400, 0x1D41A, 0x1D7CE)
            "italic" -> when (ch) {
                'h' -> "\u210E"
                else -> mapMath(ch, 0x1D434, 0x1D44E, -1)
            }
            "bold_italic" -> mapMath(ch, 0x1D468, 0x1D482, 0x1D7CE)
            "sans_bold" -> mapMath(ch, 0x1D5D4, 0x1D5EE, 0x1D7EC)
            "sans_italic" -> mapMath(ch, 0x1D608, 0x1D622, -1)
            "script" -> SCRIPT_EXCEPTIONS[ch] ?: mapMath(ch, 0x1D49C, 0x1D4B6, -1)
            "bold_script" -> mapMath(ch, 0x1D4D0, 0x1D4EA, 0x1D7CE)
            "fraktur" -> FRAKTUR_EXCEPTIONS[ch] ?: mapMath(ch, 0x1D504, 0x1D51E, -1)
            "bold_fraktur" -> mapMath(ch, 0x1D56C, 0x1D586, 0x1D7CE)
            "double_struck" -> DOUBLE_STRUCK_EXCEPTIONS[ch] ?: mapMath(ch, 0x1D538, 0x1D552, 0x1D7D8)
            "mono" -> mapMath(ch, 0x1D670, 0x1D68A, 0x1D7F6)
            else -> ch.toString()
        }
    }

    private fun isCyrillicLetter(ch: Char): Boolean {
        return ch in 'А'..'я' || ch == 'Ё' || ch == 'ё'
    }

    private fun mapMath(ch: Char, upperStart: Int, lowerStart: Int, digitStart: Int): String {
        return when {
            ch in 'A'..'Z' -> String(Character.toChars(upperStart + (ch - 'A')))
            ch in 'a'..'z' -> String(Character.toChars(lowerStart + (ch - 'a')))
            digitStart >= 0 && ch in '0'..'9' -> String(Character.toChars(digitStart + (ch - '0')))
            else -> ch.toString()
        }
    }

    private val GLITCH_MID = charArrayOf('\u0334', '\u0335', '\u0336', '\u0337', '\u0338')
    private val GLITCH_UP = charArrayOf('\u030D', '\u030E', '\u0304', '\u0305', '\u0311', '\u0315', '\u031A')

    private val SCRIPT_EXCEPTIONS = mapOf(
        'B' to "\u212C", 'E' to "\u2130", 'F' to "\u2131", 'H' to "\u210B",
        'I' to "\u2110", 'L' to "\u2112", 'M' to "\u2133", 'R' to "\u211B",
        'e' to "\u212F", 'g' to "\u210A", 'o' to "\u2134"
    )

    private val FRAKTUR_EXCEPTIONS = mapOf(
        'C' to "\u212D", 'H' to "\u210C", 'I' to "\u2111", 'R' to "\u211C", 'Z' to "\u2128"
    )

    private val DOUBLE_STRUCK_EXCEPTIONS = mapOf(
        'C' to "\u2102", 'H' to "\u210D", 'N' to "\u2115", 'P' to "\u2119",
        'Q' to "\u211A", 'R' to "\u211D", 'Z' to "\u2124"
    )

    private val SMALLCAPS_MAP = mapOf(
        'a' to "ᴀ", 'b' to "ʙ", 'c' to "ᴄ", 'd' to "ᴅ", 'e' to "ᴇ", 'f' to "ғ",
        'g' to "ɢ", 'h' to "ʜ", 'i' to "ɪ", 'j' to "ᴊ", 'k' to "ᴋ", 'l' to "ʟ",
        'm' to "ᴍ", 'n' to "ɴ", 'o' to "ᴏ", 'p' to "ᴘ", 'q' to "ǫ", 'r' to "ʀ",
        's' to "s", 't' to "ᴛ", 'u' to "ᴜ", 'v' to "ᴠ", 'w' to "ᴡ", 'x' to "x",
        'y' to "ʏ", 'z' to "ᴢ",
        'а' to "ᴀ", 'в' to "ʙ", 'е' to "ᴇ", 'ё' to "ᴇ̈", 'к' to "ᴋ", 'м' to "ᴍ",
        'н' to "ʜ", 'о' to "ᴏ", 'р' to "ᴘ", 'с' to "ᴄ", 'т' to "ᴛ", 'у' to "ʏ",
        'А' to "ᴀ", 'В' to "ʙ", 'Е' to "ᴇ", 'Ё' to "ᴇ̈", 'К' to "ᴋ", 'М' to "ᴍ",
        'Н' to "ʜ", 'О' to "ᴏ", 'Р' to "ᴘ", 'С' to "ᴄ", 'Т' to "ᴛ", 'У' to "ʏ"
    )

    private val ANCIENT_MAP = mapOf(
        'А' to "А", 'Б' to "Б", 'В' to "В", 'Г' to "Г", 'Д' to "Д", 'Е' to "Ѣ", 'Ё' to "Ѥ",
        'Ж' to "Ж", 'З' to "Ꙁ", 'И' to "І", 'Й' to "Ї", 'К' to "К", 'Л' to "Л", 'М' to "М",
        'Н' to "Н", 'О' to "Ѻ", 'П' to "П", 'Р' to "Р", 'С' to "Ѕ", 'Т' to "Т", 'У' to "Ѹ",
        'Ф' to "Ѳ", 'Х' to "Х", 'Ц' to "Ц", 'Ч' to "Ч", 'Ш' to "Ш", 'Щ' to "Щ", 'Ъ' to "Ъ",
        'Ы' to "Ы", 'Ь' to "Ь", 'Э' to "Є", 'Ю' to "Ю", 'Я' to "Ѧ",
        'а' to "а", 'б' to "б", 'в' to "в", 'г' to "г", 'д' to "д", 'е' to "ѣ", 'ё' to "ѥ",
        'ж' to "ж", 'з' to "ꙁ", 'и' to "і", 'й' to "ї", 'к' to "к", 'л' to "л", 'м' to "м",
        'н' to "н", 'о' to "ѻ", 'п' to "п", 'р' to "р", 'с' to "ѕ", 'т' to "т", 'у' to "ѹ",
        'ф' to "ѳ", 'х' to "х", 'ц' to "ц", 'ч' to "ч", 'ш' to "ш", 'щ' to "щ", 'ъ' to "ъ",
        'ы' to "ы", 'ь' to "ь", 'э' to "є", 'ю' to "ю", 'я' to "ѧ",
        'A' to "𝔄", 'B' to "𝔅", 'C' to "ℭ", 'D' to "𝔇", 'E' to "𝔈", 'F' to "𝔉", 'G' to "𝔊",
        'H' to "ℌ", 'I' to "ℑ", 'J' to "𝔍", 'K' to "𝔎", 'L' to "𝔏", 'M' to "𝔐", 'N' to "𝔑",
        'O' to "𝔒", 'P' to "𝔓", 'Q' to "𝔔", 'R' to "ℜ", 'S' to "𝔖", 'T' to "𝔗", 'U' to "𝔘",
        'V' to "𝔙", 'W' to "𝔚", 'X' to "𝔛", 'Y' to "𝔜", 'Z' to "ℨ",
        'a' to "𝔞", 'b' to "𝔟", 'c' to "𝔠", 'd' to "𝔡", 'e' to "𝔢", 'f' to "𝔣", 'g' to "𝔤",
        'h' to "𝔥", 'i' to "𝔦", 'j' to "𝔧", 'k' to "𝔨", 'l' to "𝔩", 'm' to "𝔪", 'n' to "𝔫",
        'o' to "𝔬", 'p' to "𝔭", 'q' to "𝔮", 'r' to "𝔯", 's' to "𝔰", 't' to "𝔱", 'u' to "𝔲",
        'v' to "𝔳", 'w' to "𝔴", 'x' to "𝔵", 'y' to "𝔶", 'z' to "𝔷"
    )

    private val CYBER_MAP = mapOf(
        'А' to "卂", 'Б' to "Ƃ", 'В' to "乃", 'Г' to "厂", 'Д' to "Д", 'Е' to "乇", 'Ё' to "乇",
        'Ж' to "Ж", 'З' to "Ӡ", 'И' to "И", 'Й' to "Й", 'К' to "长", 'Л' to "爪", 'М' to "爪",
        'Н' to "卄", 'О' to "口", 'П' to "П", 'Р' to "尺", 'С' to "匚", 'Т' to "匕", 'У' to "ㄚ",
        'Ф' to "中", 'Х' to "乂", 'Ц' to "Ц", 'Ч' to "Ч", 'Ш' to "山", 'Щ' to "山", 'Ъ' to "Ъ",
        'Ы' to "Ы", 'Ь' to "Ь", 'Э' to "Э", 'Ю' to "Ю", 'Я' to "Я",
        'а' to "卂", 'б' to "Ƃ", 'в' to "乃", 'г' to "厂", 'д' to "д", 'е' to "乇", 'ё' to "乇",
        'ж' to "ж", 'з' to "з", 'и' to "и", 'й' to "й", 'к' to "长", 'л' to "л", 'м' to "爪",
        'н' to "卄", 'о' to "口", 'п' to "п", 'р' to "尺", 'с' to "匚", 'т' to "匕", 'у' to "ㄚ",
        'ф' to "中", 'х' to "乂", 'ц' to "ц", 'ч' to "ч", 'ш' to "山", 'щ' to "山", 'ъ' to "ъ",
        'ы' to "ы", 'ь' to "ь", 'э' to "э", 'ю' to "ю", 'я' to "я",
        'A' to "卂", 'B' to "乃", 'C' to "匚", 'D' to "刀", 'E' to "乇", 'F' to "下", 'G' to "ム",
        'H' to "卄", 'I' to "工", 'J' to "丁", 'K' to "长", 'L' to "乚", 'M' to "爪", 'N' to "几",
        'O' to "口", 'P' to "尸", 'Q' to "Ɋ", 'R' to "尺", 'S' to "丂", 'T' to "匕", 'U' to "凵",
        'V' to "リ", 'W' to "山", 'X' to "乂", 'Y' to "ㄚ", 'Z' to "乙",
        'a' to "卂", 'b' to "乃", 'c' to "匚", 'd' to "刀", 'e' to "乇", 'f' to "下", 'g' to "ム",
        'h' to "卄", 'i' to "工", 'j' to "丁", 'k' to "长", 'l' to "乚", 'm' to "爪", 'n' to "几",
        'o' to "口", 'p' to "尸", 'q' to "Ɋ", 'r' to "尺", 's' to "丂", 't' to "匕", 'u' to "凵",
        'v' to "リ", 'w' to "山", 'x' to "乂", 'y' to "ㄚ", 'z' to "乙"
    )

    private val RUNES_MAP = mapOf(
        'А' to "Λ", 'Б' to "Ƃ", 'В' to "Β", 'Г' to "Γ", 'Д' to "Δ", 'Е' to "Σ", 'Ё' to "Σ",
        'Ж' to "Ж", 'З' to "Ζ", 'И' to "И", 'Й' to "Й", 'К' to "Κ", 'Л' to "Λ", 'М' to "Μ",
        'Н' to "Η", 'О' to "Θ", 'П' to "Π", 'Р' to "Ρ", 'С' to "Ϲ", 'Т' to "Τ", 'У' to "Υ",
        'Ф' to "Φ", 'Х' to "Χ", 'Ц' to "Џ", 'Ч' to "Ч", 'Ш' to "Ψ", 'Щ' to "Ψ", 'Э' to "Ξ",
        'а' to "α", 'б' to "ɓ", 'в' to "β", 'г' to "γ", 'д' to "δ", 'е' to "ε", 'ё' to "ε",
        'з' to "ζ", 'и' to "ι", 'к' to "κ", 'л' to "λ", 'м' to "μ", 'н' to "η", 'о' to "σ",
        'п' to "π", 'р' to "ρ", 'с' to "ς", 'т' to "τ", 'у' to "υ", 'ф' to "φ", 'х' to "χ",
        'ш' to "ω", 'щ' to "ψ",
        'A' to "Λ", 'B' to "Β", 'C' to "Ϲ", 'D' to "Δ", 'E' to "Σ", 'F' to "Ғ", 'G' to "Ǥ",
        'H' to "Η", 'I' to "Ι", 'K' to "Κ", 'L' to "Λ", 'M' to "Μ", 'N' to "Ν", 'O' to "Θ",
        'P' to "Ρ", 'R' to "Я", 'S' to "Ѕ", 'T' to "Τ", 'U' to "Ц", 'W' to "Ψ", 'X' to "Χ", 'Y' to "Υ", 'Z' to "Ζ",
        'a' to "α", 'b' to "β", 'c' to "ς", 'd' to "δ", 'e' to "ε", 'g' to "γ", 'h' to "η",
        'i' to "ι", 'k' to "κ", 'l' to "λ", 'm' to "μ", 'n' to "η", 'o' to "σ", 'p' to "ρ",
        'r' to "г", 's' to "ѕ", 't' to "τ", 'u' to "υ", 'v' to "ν", 'w' to "ω", 'x' to "χ", 'y' to "γ", 'z' to "ζ"
    )

    private val LEET_MAP = mapOf(
        'А' to "4", 'а' to "4", 'В' to "8", 'в' to "8", 'Е' to "3", 'е' to "3", 'Ё' to "3", 'ё' to "3",
        'З' to "3", 'з' to "3", 'И' to "1", 'и' to "1", 'О' to "0", 'о' to "0", 'С' to "(", 'с' to "(",
        'Т' to "7", 'т' to "7", 'Ч' to "4", 'ч' to "4",
        'A' to "4", 'a' to "4", 'B' to "8", 'b' to "8", 'E' to "3", 'e' to "3", 'G' to "6", 'g' to "9",
        'I' to "1", 'i' to "1", 'L' to "1", 'l' to "1", 'O' to "0", 'o' to "0", 'S' to "5", 's' to "5",
        'T' to "7", 't' to "7", 'Z' to "2", 'z' to "2"
    )

    private val UPSIDE_MAP = mapOf(
        'a' to "ɐ", 'b' to "q", 'c' to "ɔ", 'd' to "p", 'e' to "ǝ", 'f' to "ɟ", 'g' to "ƃ",
        'h' to "ɥ", 'i' to "ᴉ", 'j' to "ɾ", 'k' to "ʞ", 'l' to "ן", 'm' to "ɯ", 'n' to "u",
        'o' to "o", 'p' to "d", 'q' to "b", 'r' to "ɹ", 's' to "s", 't' to "ʇ", 'u' to "n",
        'v' to "ʌ", 'w' to "ʍ", 'x' to "x", 'y' to "ʎ", 'z' to "z",
        'A' to "∀", 'B' to "𐐒", 'C' to "Ɔ", 'D' to "◖", 'E' to "Ǝ", 'F' to "Ⅎ", 'G' to "⅁",
        'H' to "H", 'I' to "I", 'J' to "ſ", 'K' to "⋊", 'L' to "˥", 'M' to "W", 'N' to "N",
        'O' to "O", 'P' to "Ԁ", 'Q' to "Ό", 'R' to "ᴚ", 'S' to "S", 'T' to "⊥", 'U' to "∩",
        'V' to "Λ", 'W' to "M", 'X' to "X", 'Y' to "⅄", 'Z' to "Z",
        'а' to "ɐ", 'б' to "ƍ", 'в' to "ʚ", 'г' to "ɹ", 'д' to "ɓ", 'е' to "ǝ", 'ё' to "ǝ",
        'ж' to "ж", 'з' to "ε", 'и' to "и", 'й' to "и", 'к' to "ʞ", 'л' to "v", 'м' to "w",
        'н' to "н", 'о' to "о", 'п' to "u", 'р' to "d", 'с' to "ɔ", 'т' to "ɯ", 'у' to "ʎ",
        'ф' to "ф", 'х' to "х", 'ц' to "ǹ", 'ч' to "Һ", 'ш' to "m", 'щ' to "m", 'ы' to "ıq",
        'э' to "є", 'ю' to "oı", 'я' to "ʁ",
        'А' to "∀", 'В' to "ꓭ", 'Г' to "⅃", 'Е' to "Ǝ", 'З' to "Ɛ", 'И' to "И", 'К' to "⋊",
        'Л' to "V", 'М' to "W", 'Н' to "H", 'О' to "O", 'П' to "U", 'Р' to "Ԁ", 'С' to "Ɔ",
        'Т' to "⊥", 'У' to "⅄", 'Ш' to "M", 'Я' to "ʁ",
        '!' to "¡", '?' to "¿", '.' to "˙", ',' to "'", '(' to ")", ')' to "("
    )

    // ===================== LIVE PREVIEW FONT STUDIO UI =====================

    @JvmStatic
    fun openFontStudioDialog(context: Context?, initialTab: Int = 0) {
        ensureInitialized()
        val ctx = context ?: getActivity() ?: return
        var currentTab = initialTab
        var searchQuery = ""
        var sampleText = "Привет, MediaTok! Hello 123"
        var dialogRef: AlertDialog? = null

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(14f)
            setPadding(p, AndroidUtilities.dp(6f), p, AndroidUtilities.dp(4f))
        }

        // Tabs row
        val tabsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tabUiBtn = createPillButton(ctx, "Клиент (${BUNDLED_FONTS.size + listCustomFonts().size})")
        val tabChatBtn = createPillButton(ctx, "Текст чата (${CHAT_TEXT_STYLES.size})")
        tabsRow.addView(tabUiBtn, LayoutHelper.createLinear(0, 38, 1f, 0, 0, 4, 0))
        tabsRow.addView(tabChatBtn, LayoutHelper.createLinear(0, 38, 1f, 4, 0, 0, 0))
        root.addView(tabsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f))

        // Search / Live sample input
        val inputBox = EditTextBoldCursor(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            isSingleLine = true
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(6f), AndroidUtilities.dp(8f), AndroidUtilities.dp(6f))
        }
        root.addView(inputBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 6f))

        // Action row (Import / Reset)
        val actionsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val leftAction = createPillButton(ctx, "+ Загрузить .ttf/.otf")
        val rightAction = createPillButton(ctx, "Сбросить")
        actionsRow.addView(leftAction, LayoutHelper.createLinear(0, 34, 1.2f, 0, 0, 4, 0))
        actionsRow.addView(rightAction, LayoutHelper.createLinear(0, 34, 0.8f, 4, 0, 0, 0))
        root.addView(actionsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f))

        // Scrollable list container
        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(ctx).apply {
            addView(listContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 370))

        fun styleTabs() {
            val accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton)
            val mutedBg = Color.argb(26, 128, 128, 128)
            val whiteText = Color.WHITE
            val normalText = Theme.getColor(Theme.key_dialogTextBlack)

            applyPillStyle(tabUiBtn, if (currentTab == 0) accent else mutedBg, if (currentTab == 0) whiteText else normalText)
            applyPillStyle(tabChatBtn, if (currentTab == 1) accent else mutedBg, if (currentTab == 1) whiteText else normalText)

            if (currentTab == 0) {
                leftAction.text = "+ Загрузить .ttf / .otf"
                leftAction.visibility = View.VISIBLE
                rightAction.text = "Системный"
                inputBox.hint = "Поиск шрифта (напр. Montserrat, Pixel, Mono)..."
                if (inputBox.text?.toString() != searchQuery) inputBox.setText(searchQuery)
            } else {
                leftAction.text = "Команда: .f <стиль> <текст>"
                leftAction.visibility = View.VISIBLE
                rightAction.text = "Выкл авто-стиль"
                inputBox.hint = "Текст для живого предпросмотра..."
                if (inputBox.text?.toString() != sampleText) inputBox.setText(sampleText)
            }
            applyPillStyle(leftAction, mutedBg, normalText)
            applyPillStyle(rightAction, mutedBg, normalText)
        }

        fun rebuildList() {
            listContainer.removeAllViews()
            if (currentTab == 0) {
                val query = searchQuery.trim().lowercase(Locale.ROOT)
                val selectedId = activeUiFontId

                // Custom imported fonts section
                val customFonts = listCustomFonts().filter {
                    query.isEmpty() || it.displayName.lowercase(Locale.ROOT).contains(query)
                }
                if (customFonts.isNotEmpty()) {
                    listContainer.addView(createCategoryHeader(ctx, "Мои шрифты (.ttf / .otf)"))
                    for (cf in customFonts) {
                        val fullId = PREFIX_CUSTOM + cf.fileName
                        val tf = loadTypefaceById(fullId)
                        val isSelected = selectedId == fullId
                        val row = createFontPreviewRow(
                            ctx = ctx,
                            title = cf.displayName + if (isSelected) "  ✓" else "",
                            preview = "Привет, MediaTok! AaBbCc 0123",
                            typeface = tf,
                            selected = isSelected,
                            onClick = {
                                setUiFontId(fullId)
                                toast("Шрифт клиента: ${cf.displayName}")
                                rebuildList()
                                promptRestartIfNeeded(ctx as? Activity ?: getActivity())
                            },
                            onLongClick = {
                                AlertDialog.Builder(ctx)
                                    .setTitle("Удалить шрифт?")
                                    .setMessage("Удалить импортированный шрифт «${cf.displayName}»?")
                                    .setPositiveButton("Удалить") { _, _ ->
                                        cf.file.delete()
                                        previewCache.remove(fullId)
                                        if (activeUiFontId == fullId) setUiFontId("")
                                        rebuildList()
                                    }
                                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                                    .show()
                            }
                        )
                        listContainer.addView(row)
                    }
                }

                // Bundled 48 fonts grouped by category
                var lastCat = ""
                for (font in BUNDLED_FONTS) {
                    if (query.isNotEmpty() &&
                        !font.name.lowercase(Locale.ROOT).contains(query) &&
                        !font.category.lowercase(Locale.ROOT).contains(query) &&
                        !font.id.contains(query)
                    ) continue
                    if (font.category != lastCat) {
                        lastCat = font.category
                        listContainer.addView(createCategoryHeader(ctx, lastCat))
                    }
                    val fullId = PREFIX_BUNDLED + font.id
                    val tf = loadTypefaceById(fullId)
                    val isSelected = selectedId == fullId
                    val row = createFontPreviewRow(
                        ctx = ctx,
                        title = font.name + if (isSelected) "  ✓" else "",
                        preview = "Привет, MediaTok! AaBbCc 0123",
                        typeface = tf,
                        selected = isSelected,
                        onClick = {
                            setUiFontId(fullId)
                            toast("Шрифт клиента: ${font.name}")
                            rebuildList()
                            promptRestartIfNeeded(ctx as? Activity ?: getActivity())
                        },
                        onLongClick = null
                    )
                    listContainer.addView(row)
                }
            } else {
                val previewInput = sampleText.ifBlank { "Привет, MediaTok! Hello 123" }
                val activeStyle = activeChatStyleId
                var lastCat = ""
                for (style in CHAT_TEXT_STYLES) {
                    if (style.category != lastCat) {
                        lastCat = style.category
                        listContainer.addView(createCategoryHeader(ctx, lastCat))
                    }
                    val isSelected = activeStyle == style.id
                    val rendered = transformSmart(previewInput, style.id)
                    val row = createFontPreviewRow(
                        ctx = ctx,
                        title = "${style.name}  (.f ${style.id})" + if (isSelected) "  ✓" else "",
                        preview = rendered,
                        typeface = null,
                        selected = isSelected,
                        onClick = {
                            if (isSelected) {
                                setActiveChatStyleId("")
                                toast("Авто-шрифт сообщений ВЫКЛ")
                            } else {
                                setActiveChatStyleId(style.id)
                                toast("Авто-шрифт сообщений: ${style.name}")
                            }
                            rebuildList()
                        },
                        onLongClick = {
                            AndroidUtilities.addToClipboard(rendered)
                            toast("Скопировано: $rendered")
                        }
                    )
                    listContainer.addView(row)
                }
            }
        }

        tabUiBtn.setOnClickListener {
            if (currentTab != 0) {
                currentTab = 0
                styleTabs()
                rebuildList()
            }
        }
        tabChatBtn.setOnClickListener {
            if (currentTab != 1) {
                currentTab = 1
                styleTabs()
                rebuildList()
            }
        }
        leftAction.setOnClickListener {
            if (currentTab == 0) {
                dialogRef?.dismiss()
                startImportFontPicker(ctx as? Activity ?: getActivity())
            } else {
                toast("Долгое нажатие на стиль — скопировать текст. Или напишите в чат: .f cyber Привет!")
            }
        }
        rightAction.setOnClickListener {
            if (currentTab == 0) {
                setUiFontId("")
                toast("Установлен системный шрифт")
                rebuildList()
                promptRestartIfNeeded(ctx as? Activity ?: getActivity())
            } else {
                setActiveChatStyleId("")
                toast("Авто-шрифт сообщений выключен")
                rebuildList()
            }
        }

        inputBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString().orEmpty()
                if (currentTab == 0) {
                    if (txt != searchQuery) {
                        searchQuery = txt
                        rebuildList()
                    }
                } else {
                    if (txt != sampleText) {
                        sampleText = txt
                        rebuildList()
                    }
                }
            }
        })

        styleTabs()
        rebuildList()

        val builder = AlertDialog.Builder(ctx)
            .setTitle("Шрифты MediaTok")
            .setView(root)
            .setPositiveButton(LocaleController.getString(R.string.Close), null)
        dialogRef = builder.show()
    }

    private fun promptRestartIfNeeded(activity: Activity?) {
        val act = activity ?: getActivity() ?: return
        AlertDialog.Builder(act)
            .setTitle("Применить шрифт везде?")
            .setMessage("Шрифт уже активен для новых экранов. Перезапустить MediaTok сейчас, чтобы обновить весь интерфейс?")
            .setPositiveButton("Перезапустить") { _, _ ->
                AppRestartHelper.triggerRebirth()
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun createPillButton(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(6f), AndroidUtilities.dp(8f), AndroidUtilities.dp(6f))
        }
    }

    private fun applyPillStyle(tv: TextView, bgColor: Int, textColor: Int) {
        val gd = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = AndroidUtilities.dp(10f).toFloat()
        }
        tv.background = gd
        tv.setTextColor(textColor)
    }

    private fun createCategoryHeader(ctx: Context, title: String): TextView {
        return TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
            setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f))
        }
    }

    private fun createFontPreviewRow(
        ctx: Context,
        title: String,
        preview: String,
        typeface: Typeface?,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?
    ): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padH = AndroidUtilities.dp(10f)
            val padV = AndroidUtilities.dp(8f)
            setPadding(padH, padV, padH, padV)
            val bg = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(10f).toFloat()
                if (selected) {
                    val accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton)
                    setColor(Color.argb(34, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    setStroke(AndroidUtilities.dp(1.5f), accent)
                } else {
                    setColor(Color.argb(14, 128, 128, 128))
                }
            }
            background = bg
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }
        val titleTv = TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        val previewTv = TextView(ctx).apply {
            text = preview
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            if (typeface != null) {
                this.typeface = typeface
            }
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        box.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        box.addView(previewTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(3f), 0, AndroidUtilities.dp(3f))
            addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        return wrapper
    }

    private fun getActivity(): Activity? = LaunchActivity.getLastFragment()?.parentActivity

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            val ctx = ApplicationLoader.applicationContext ?: return@runOnUIThread
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
