package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import tw.nekomimi.nekogram.helpers.AppRestartHelper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Full Language Studio for MediaTok Android:
 * 1) 62 verified live public Telegram language packs (tg://setlanguage?lang=...)
 * 2) 4 instant offline MediaTok presets ("Живой / Умер", "Киберпанк", "Царский", "Кошачий")
 * 3) Full-client runtime string override editor covering ALL ~5,500+ Telegram R.string keys!
 */
object MediaTokLangHelper {

    private const val PREFS_NAME = "mediatok_lang_overrides"
    private const val KEY_JSON = "overrides_json"

    data class PublicLangPack(
        val slug: String,
        val title: String,
        val desc: String,
        val category: String
    )

    data class LangEntry(
        val key: String,
        val resId: Int,
        val hint: String = ""
    )

    val PUBLIC_PACKS: List<PublicLangPack> = listOf(
        // Мемы, юмор и сленг
        PublicLangPack("therussianslang", "Сленг", "Разговорный молодёжный сленг", "Мемы и сленг"),
        PublicLangPack("idontno1", "Смешной без мата", "Мега смешной перевод без мата", "Мемы и сленг"),
        PublicLangPack("userfriendly", "Несерьёзный", "Меньше официальности, больше кайфа", "Мемы и сленг"),
        PublicLangPack("rusmltrs", "всё с маленькой буквы", "Эстетичный минимализм без заглавных", "Мемы и сленг"),
        PublicLangPack("mymylang", "вацап.", "Маленькими буквами и с точкой.", "Мемы и сленг"),
        PublicLangPack("iseeyouforevernow", "оно говорит со мной?", "Криповый сломанный стиль", "Мемы и сленг"),
        PublicLangPack("sadnesssad", "Печальный язык", "Грустный вайб для всего ТГ", "Мемы и сленг"),
        PublicLangPack("shizoid", "Шизоид", "Ироничный мемный интерфейс", "Мемы и сленг"),
        PublicLangPack("abobusgram", "Абобусграм", "Мемный стиль Абобус", "Мемы и сленг"),
        PublicLangPack("draining", "Drain / Дед-инсайд", "Мрачный дрейн-стиль", "Мемы и сленг"),
        PublicLangPack("tokianghoul", "Ghoul (Дед инсайд)", "Для настоящих гулей 1000-7", "Мемы и сленг"),
        PublicLangPack("piratesss", "PiRaTeS", "Пиратский стиль интерфейса", "Мемы и сленг"),
        PublicLangPack("goblincoore", "Goblincore", "Лёгкий язык с гоблинами без мата", "Мемы и сленг"),
        PublicLangPack("akademiad", "Dark Academia", "Тёмная академия и эстетика", "Мемы и сленг"),
        PublicLangPack("churchwex", "Церковный", "Церковно-духовный стиль", "Мемы и сленг"),
        PublicLangPack("udiikwoheh", "Нефорский", "Альтернативный сленг", "Мемы и сленг"),
        PublicLangPack("syrncorelanguage", "Русский + секунды", "Точное время с секундами", "Мемы и сленг"),
        PublicLangPack("teamgram", "Tgram (уютный + сек.)", "Уютный перевод с секундами", "Мемы и сленг"),
        PublicLangPack("tmngram", "ТюменьГрам", "Шуточный региональный перевод", "Мемы и сленг"),
        PublicLangPack("russianbyg02m0n1k", "Шуточный от G02m0n1k", "Известные фразы и шутки", "Мемы и сленг"),

        // Котики и милые
        PublicLangPack("kiska", "Киска / Кошачий", "Мяу-интерфейс и котики", "Котики и милые"),
        PublicLangPack("catttish", "Catttish", "Кошачий сленг", "Котики и милые"),
        PublicLangPack("meowtg", "MeowTG", "Мурлыкающий Telegram", "Котики и милые"),
        PublicLangPack("liffan", "Kotiki", "Язык с котиками от Liffan", "Котики и милые"),
        PublicLangPack("krutyshka", "Крутышка и котики", "Милый стиль с котиками", "Котики и милые"),
        PublicLangPack("fluffyru", "Пуфыстенький", "Фуррёвый мягкий перевод", "Котики и милые"),
        PublicLangPack("burviss", "Nyan", "Котики и лёгкий сленг :з", "Котики и милые"),
        PublicLangPack("ruwussian", "РУвУсский (UwU)", "Милый UwU / аниме-стиль", "Котики и милые"),
        PublicLangPack("angeltoangels", "Angel Tongue", "Ангелочки, котики, каомодзи", "Котики и милые"),
        PublicLangPack("sad0vira1", "Садовый рай", "Цветочная тематика без мата", "Котики и милые"),
        PublicLangPack("krasotcka", "Tigers", "Для любителей тигров", "Котики и милые"),

        // Игры
        PublicLangPack("stalkeryazik", "S.T.A.L.K.E.R.", "Call of The Zone (Зона)", "Игры"),
        PublicLangPack("atomicheart20", "Atomic Heart", "Вселенная игры Atomic Heart", "Игры"),
        PublicLangPack("modernwarfare", "Call of Duty: MW", "Стиль оригинальной трилогии MW", "Игры"),
        PublicLangPack("borderland", "Borderlands", "Язык Пандоры и Искателей", "Игры"),
        PublicLangPack("binding-of-isaac", "The Binding of Isaac", "Абадоооон / стиль Айзека", "Игры"),
        PublicLangPack("genshin-8", "Genshin Impact", "Перевод по Геншину", "Игры"),
        PublicLangPack("ultimatehope228", "Danganronpa", "Отчаяние и надежда", "Игры"),

        // Аниме, кино и сериалы
        PublicLangPack("naturo", "Наруто (Синоби)", "Перевод по вселенной Наруто", "Аниме и кино"),
        PublicLangPack("evang3li0n", "Evangelion", "По Евангелиону без мата", "Аниме и кино"),
        PublicLangPack("jjba", "JoJo's Bizarre Adv.", "Аниме-стиль ДжоДжо", "Аниме и кино"),
        PublicLangPack("bleach", "Bleach", "Аниме-стиль Блич", "Аниме и кино"),
        PublicLangPack("tokiiiio", "Токийские мстители", "Перевод по Tokyo Revengers", "Аниме и кино"),
        PublicLangPack("bungoustraydogslang", "Бродячие псы", "Bungou Stray Dogs", "Аниме и кино"),
        PublicLangPack("middle-earths", "Средиземье", "Властелин колец и Хоббит", "Аниме и кино"),
        PublicLangPack("marvel-ru", "Marvel", "Вселенная супергероев Marvel", "Аниме и кино"),
        PublicLangPack("fightclublmf", "Бойцовский клуб", "Первое правило клуба...", "Аниме и кино"),
        PublicLangPack("223222", "Пираты Карибского моря", "Йо-хо-хо и ром", "Аниме и кино"),
        PublicLangPack("supernaturallanguage", "Сверхъестественное", "Для фанатов Винчестеров", "Аниме и кино"),
        PublicLangPack("katamaranovian", "Внутри Лапенко", "Катамарановский стиль", "Аниме и кино"),
        PublicLangPack("metal-femily", "Metal Family", "Перевод по Metal Family", "Аниме и кино"),

        // Исторические и кодировки
        PublicLangPack("oldrus111", "Дореформенный Русскій", "ѣ, ѳ, і и твёрдые знаки", "Исторические и коды"),
        PublicLangPack("classicrussian", "Русскiй (царский)", "Дореволюционная стилистика", "Исторические и коды"),
        PublicLangPack("vahtang", "Старославянский", "Древнерусский слог", "Исторические и коды"),
        PublicLangPack("glagol", "Глаголица", "Первый славянский алфавит", "Исторические и коды"),
        PublicLangPack("aprelteam", "Советский (СССР)", "Товарищи и комсомол", "Исторические и коды"),
        PublicLangPack("ilovecp1251", "бНОПНЯ (CP1251)", "Сломанная кодировка из 2000-х", "Исторические и коды"),
        PublicLangPack("koi7codemap", "KOI-7 H1", "Ретро-транслит кодировка", "Исторические и коды"),

        // 18+ приколы
        PublicLangPack("dblogrm", "DebiloGram (18+)", "Мемный перевод с матом", "18+ приколы"),
        PublicLangPack("ebllogram-ru", "Еблограм (18+)", "Легендарный Еблограм", "18+ приколы"),
        PublicLangPack("dlgram", "Долбограм (18+)", "Жёсткий сленг и угар", "18+ приколы"),
        PublicLangPack("piizdosgram", "Пиздосграм (18+)", "Поэзия из матов", "18+ приколы"),
        PublicLangPack("dpxlwop", "Ахуительный язык (18+)", "Прикольчики и мат", "18+ приколы"),
        PublicLangPack("govor", "ТоксикРашн (18+)", "Максимальная токсичность", "18+ приколы"),
        PublicLangPack("chillish", "Chill language (18+)", "Шуточный перевод на чилле", "18+ приколы"),
        PublicLangPack("poshlatina343", "Премиум телега (18+)", "Забавный перевод + секунды", "18+ приколы")
    )

    val POPULAR_KEYS: List<Pair<String, String>> = listOf(
        "Online" to "В сети",
        "Lately" to "Был(а) недавно",
        "WithinAWeek" to "Был(а) на этой неделе",
        "WithinAMonth" to "Был(а) в этом месяце",
        "ALongTimeAgo" to "Был(а) очень давно",
        "LastSeenFormatted" to "Был(а) %1\$s (время)",
        "LastSeenDateFormatted" to "Был(а) %1\$s (дата)",
        "LastSeen" to "Был(а)",
        "Typing" to "Печатает...",
        "AreTyping" to "Печатают...",
        "RecordingAudio" to "Записывает голосовое...",
        "RecordingRound" to "Записывает видеосообщение...",
        "SendingPhoto" to "Отправляет фото...",
        "SendingVideo" to "Отправляет видео...",
        "SavedMessages" to "Избранное",
        "ArchivedChats" to "Архив",
        "FilterAllChats" to "Все чаты",
        "Message" to "Поле «Сообщение»",
        "Send" to "Отправить",
        "Reply" to "Ответить",
        "Forward" to "Переслать",
        "Copy" to "Копировать",
        "Edit" to "Изменить",
        "Delete" to "Удалить",
        "DeleteForAll" to "Удалить у всех",
        "PinMessage" to "Закрепить",
        "PinnedMessage" to "Закреплённое сообщение",
        "EditedMessage" to "Пометка «изменено»",
        "UnreadMessages" to "Непрочитанные сообщения",
        "Draft" to "Черновик",
        "Settings" to "Настройки",
        "Contacts" to "Контакты",
        "Calls" to "Звонки",
        "Search" to "Поиск",
        "NewGroup" to "Новая группа",
        "NewChannel" to "Новый канал",
        "NewSecretChat" to "Секретный чат",
        "HiddenName" to "Удалённый аккаунт",
        "Bot" to "Бот",
        "MuteNotifications" to "Выключить уведомления",
        "UnmuteNotifications" to "Включить уведомления",
        "BlockContact" to "Заблокировать",
        "Unblock" to "Разблокировать",
        "UserBio" to "О себе",
        "Username" to "Имя пользователя",
        "Phone" to "Телефон",
        "AttachPhoto" to "Фото",
        "AttachVideo" to "Видео",
        "AttachDocument" to "Файл",
        "AttachMusic" to "Музыка",
        "ShareFile" to "Поделиться",
        "SaveToGallery" to "Сохранить в галерею",
        "Yesterday" to "Вчера",
        "Save" to "Сохранить",
        "Cancel" to "Отмена",
        "Close" to "Закрыть",
        "Clear" to "Очистить"
    )

    val PRESETS: List<Pair<String, Map<String, String>>> = listOf(
        "Живой / Умер 💀" to mapOf(
            "Online" to "живой 🟢",
            "Lately" to "умер 💀",
            "WithinAWeek" to "сдох на неделе 💀",
            "WithinAMonth" to "разлагается месяц 🪦",
            "ALongTimeAgo" to "давно в гробу ⚰️",
            "LastSeenFormatted" to "был живой %1\$s",
            "LastSeenDateFormatted" to "умер %1\$s",
            "Typing" to "строчит бред...",
            "AreTyping" to "строчат бред...",
            "RecordingAudio" to "ноет в микро...",
            "RecordingRound" to "кривляется на камеру...",
            "SendingPhoto" to "кидает фотку...",
            "SendingVideo" to "кидает видос...",
            "SavedMessages" to "Тайник",
            "ArchivedChats" to "Свалка",
            "FilterAllChats" to "Все тёрки",
            "Message" to "Чиркани чё-нить...",
            "Send" to "Кинуть",
            "Reply" to "Ответить по факту",
            "Forward" to "Слить",
            "Copy" to "Скопипастить",
            "Edit" to "Переобуться",
            "Delete" to "Сжечь",
            "DeleteForAll" to "Сжечь у всех",
            "PinMessage" to "Прибить",
            "PinnedMessage" to "Прибитое",
            "EditedMessage" to "переобулся",
            "UnreadMessages" to "Пропущенное",
            "Draft" to "Недописал",
            "Settings" to "Крутилки",
            "Contacts" to "Кенты",
            "Calls" to "Звонилка",
            "Search" to "Шерлок",
            "HiddenName" to "Призрак 👻",
            "Bot" to "Железяка 🤖",
            "MuteNotifications" to "Заткнуть",
            "UnmuteNotifications" to "Размутить",
            "BlockContact" to "В бан",
            "Unblock" to "Помиловать",
            "SaveToGallery" to "Стырить в галерею"
        ),
        "Киберпанк 2077 ⚡" to mapOf(
            "Online" to "[ONLINE // ACTIVE]",
            "Lately" to "[OFFLINE // SIGNAL LOST]",
            "WithinAWeek" to "[OFFLINE > 7D]",
            "WithinAMonth" to "[OFFLINE > 30D]",
            "ALongTimeAgo" to "[NODE TERMINATED]",
            "LastSeenFormatted" to "last ping %1\$s",
            "LastSeenDateFormatted" to "last ping %1\$s",
            "Typing" to "[INJECTING PAYLOAD...]",
            "AreTyping" to "[MULTI-PAYLOAD...]",
            "RecordingAudio" to "[AUDIO STREAM...]",
            "RecordingRound" to "[VIDEO FEED...]",
            "SavedMessages" to "VAULT // LOCAL",
            "ArchivedChats" to "COLD STORAGE",
            "FilterAllChats" to "ALL NODES",
            "Message" to "> input_command...",
            "Send" to "TRANSMIT",
            "Delete" to "PURGE",
            "DeleteForAll" to "GLOBAL PURGE",
            "EditedMessage" to "patched",
            "Settings" to "SYS_CONFIG",
            "Contacts" to "NET_PEERS",
            "Search" to "GREP",
            "HiddenName" to "GHOST_NODE",
            "Bot" to "AI_DAEMON"
        ),
        "Царскій 👑" to mapOf(
            "Online" to "въ сѣти",
            "Lately" to "почивалъ намедни",
            "WithinAWeek" to "не видать съ седмицы",
            "WithinAMonth" to "канулъ въ Лету",
            "ALongTimeAgo" to "исчезъ во мракѣ вѣковъ",
            "LastSeenFormatted" to "изволилъ быть %1\$s",
            "LastSeenDateFormatted" to "изволилъ быть %1\$s",
            "Typing" to "изволитъ писать...",
            "AreTyping" to "изволятъ писать...",
            "RecordingAudio" to "глаголетъ въ трубу...",
            "RecordingRound" to "снимаетъ синематографъ...",
            "SavedMessages" to "Сундукъ",
            "ArchivedChats" to "Чуланъ",
            "Message" to "Начертать грамоту...",
            "Send" to "Отправити",
            "Reply" to "Отвѣтствовать",
            "Delete" to "Предати огню",
            "EditedMessage" to "исправлено",
            "Settings" to "Уложеніе",
            "Contacts" to "Знакомцы",
            "HiddenName" to "Усопшій аккъ"
        ),
        "Кошачий / Мяу 🐾" to mapOf(
            "Online" to "мурлычет в сети 🐱",
            "Lately" to "спит на батарее 💤",
            "WithinAWeek" to "ушёл гулять по крышам",
            "WithinAMonth" to "пропал за валерьянкой",
            "ALongTimeAgo" to "убежал в которай",
            "LastSeenFormatted" to "мурлыкал %1\$s",
            "LastSeenDateFormatted" to "мурлыкал %1\$s",
            "Typing" to "тыгыдыкает по клаве...",
            "AreTyping" to "тыгыдыкают...",
            "RecordingAudio" to "мяукает в микро...",
            "RecordingRound" to "показывает лапки...",
            "SavedMessages" to "Коробка 📦",
            "ArchivedChats" to "Лоток",
            "Message" to "Мяукнуть...",
            "Send" to "Мяу!",
            "Delete" to "Закопать",
            "EditedMessage" to "перемяукал",
            "Settings" to "Когтеточка",
            "Contacts" to "Котики 🐾",
            "HiddenName" to "Призрачный кот"
        )
    )

    @Volatile private var initialized = false
    @Volatile private var hasOverridesFlag = false
    private val overridesByKey = ConcurrentHashMap<String, String>()
    private val overridesByResId = ConcurrentHashMap<Int, String>()
    private val bypassOverride = ThreadLocal<Boolean>()

    @Volatile private var allEntriesCache: List<LangEntry>? = null
    private val nameToResId = ConcurrentHashMap<String, Int>()
    private val resIdToName = ConcurrentHashMap<Int, String>()

    private fun prefs(): SharedPreferences? {
        val ctx = ApplicationLoader.applicationContext ?: return null
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val p = prefs() ?: return
            val raw = p.getString(KEY_JSON, "").orEmpty()
            if (raw.isNotBlank()) {
                try {
                    val obj = JSONObject(raw)
                    val it = obj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val v = obj.optString(k, "")
                        if (k.isNotBlank() && v.isNotEmpty()) {
                            overridesByKey[k] = v
                        }
                    }
                } catch (e: Throwable) {
                    FileLog.e(e)
                }
            }
            hasOverridesFlag = overridesByKey.isNotEmpty()
            initialized = true
            if (hasOverridesFlag) {
                Utilities.globalQueue.postRunnable {
                    ensureAllEntriesIndexed()
                    rebuildResIdMap()
                }
            }
        }
    }

    private fun ensureAllEntriesIndexed(): List<LangEntry> {
        allEntriesCache?.let { return it }
        synchronized(nameToResId) {
            allEntriesCache?.let { return it }
            val list = ArrayList<LangEntry>(6000)
            try {
                val fields = R.string::class.java.declaredFields
                for (f in fields) {
                    try {
                        val name = f.name
                        val resId = f.getInt(null)
                        nameToResId[name] = resId
                        resIdToName[resId] = name
                        list.add(LangEntry(name, resId))
                    } catch (_: Throwable) {
                    }
                }
            } catch (e: Throwable) {
                FileLog.e(e)
            }
            list.sortBy { it.key.lowercase(Locale.ROOT) }
            allEntriesCache = list
            rebuildResIdMap()
            return list
        }
    }

    private fun rebuildResIdMap() {
        overridesByResId.clear()
        for ((k, v) in overridesByKey) {
            val id = nameToResId[k]
            if (id != null && id != 0) {
                overridesByResId[id] = v
            }
        }
    }

    private fun saveOverrides() {
        hasOverridesFlag = overridesByKey.isNotEmpty()
        rebuildResIdMap()
        val obj = JSONObject()
        for ((k, v) in overridesByKey) {
            obj.put(k, v)
        }
        prefs()?.edit()?.putString(KEY_JSON, obj.toString())?.apply()
        AndroidUtilities.runOnUIThread {
            try {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun getOverride(key: String?, resId: Int): String? {
        ensureInitialized()
        if (!hasOverridesFlag) return null
        if (bypassOverride.get() == true) return null
        if (key != null) {
            overridesByKey[key]?.let { return it }
        }
        if (resId != 0) {
            overridesByResId[resId]?.let { return it }
            val resolvedName = resIdToName[resId]
            if (resolvedName != null) {
                overridesByKey[resolvedName]?.let {
                    overridesByResId[resId] = it
                    return it
                }
            }
        }
        return null
    }

    private fun getOriginalString(key: String, resId: Int): String {
        bypassOverride.set(true)
        return try {
            val raw = if (resId != 0) LocaleController.getString(key, resId) else LocaleController.getString(key)
            if (raw.isNullOrEmpty() || raw.startsWith("LOC_ERR")) key else raw
        } catch (_: Throwable) {
            key
        } finally {
            bypassOverride.set(false)
        }
    }

    @JvmStatic
    fun getOverrideCount(): Int {
        ensureInitialized()
        return overridesByKey.size
    }

    fun applyPreset(map: Map<String, String>) {
        ensureInitialized()
        ensureAllEntriesIndexed()
        for ((k, v) in map) {
            overridesByKey[k] = v
        }
        saveOverrides()
    }

    fun setOverride(key: String, value: String) {
        ensureInitialized()
        if (value.isEmpty()) {
            overridesByKey.remove(key)
        } else {
            overridesByKey[key] = value
        }
        saveOverrides()
    }

    fun clearAllOverrides() {
        ensureInitialized()
        overridesByKey.clear()
        overridesByResId.clear()
        saveOverrides()
    }

    fun exportJson(): String {
        ensureInitialized()
        val obj = JSONObject()
        for ((k, v) in overridesByKey.toSortedMap()) {
            obj.put(k, v)
        }
        return obj.toString(2)
    }

    fun importJson(raw: String): Int {
        ensureInitialized()
        ensureAllEntriesIndexed()
        val obj = JSONObject(raw.trim())
        var count = 0
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = obj.optString(k, "")
            if (k.isNotBlank() && v.isNotEmpty()) {
                overridesByKey[k] = v
                count++
            }
        }
        if (count > 0) saveOverrides()
        return count
    }

    // ===================== STUDIO UI =====================

    @JvmStatic
    fun openLanguageStudioDialog(context: Context?, initialTab: Int = 0) {
        ensureInitialized()
        val ctx = context ?: LaunchActivity.getLastFragment()?.parentActivity ?: return
        var currentTab = initialTab // 0 = Public Catalog + Presets, 1 = Full Editor (ALL strings!), 2 = Export/Import
        var editorFilter = 0 // 0 = Popular, 1 = ALL (~5500+ strings!), 2 = Modified only
        var searchQuery = ""
        var allIndexed: List<LangEntry> = allEntriesCache ?: emptyList()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(12f)
            setPadding(p, AndroidUtilities.dp(6f), p, AndroidUtilities.dp(4f))
        }

        // Top tabs
        val tabsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tabCatalog = createPill(ctx, "Каталог (${PUBLIC_PACKS.size})")
        val tabEditor = createPill(ctx, "Фулл редактор")
        val tabIo = createPill(ctx, "Экспорт/Импорт")
        tabsRow.addView(tabCatalog, LayoutHelper.createLinear(0, 36, 1f, 0, 0, 3, 0))
        tabsRow.addView(tabEditor, LayoutHelper.createLinear(0, 36, 1.15f, 3, 0, 3, 0))
        tabsRow.addView(tabIo, LayoutHelper.createLinear(0, 36, 0.95f, 3, 0, 0, 0))
        root.addView(tabsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 6f))

        // Sub-filter row (only visible in Editor tab)
        val filterRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val fPopular = createPill(ctx, "Хиты (${POPULAR_KEYS.size})")
        val fAll = createPill(ctx, "ВСЕ строки")
        val fMod = createPill(ctx, "Правки (${overridesByKey.size})")
        filterRow.addView(fPopular, LayoutHelper.createLinear(0, 30, 1f, 0, 0, 3, 0))
        filterRow.addView(fAll, LayoutHelper.createLinear(0, 30, 1f, 3, 0, 3, 0))
        filterRow.addView(fMod, LayoutHelper.createLinear(0, 30, 1f, 3, 0, 0, 0))
        root.addView(filterRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 6f))

        // Search box
        val searchBox = EditTextBoldCursor(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            isSingleLine = true
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(6f), AndroidUtilities.dp(8f), AndroidUtilities.dp(6f))
        }
        root.addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 6f))

        val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            addView(listContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 380))

        lateinit var rebuildList: () -> Unit

        fun updateChrome() {
            val accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton)
            val muted = Color.argb(26, 128, 128, 128)
            val normal = Theme.getColor(Theme.key_dialogTextBlack)

            stylePill(tabCatalog, if (currentTab == 0) accent else muted, if (currentTab == 0) Color.WHITE else normal)
            stylePill(tabEditor, if (currentTab == 1) accent else muted, if (currentTab == 1) Color.WHITE else normal)
            stylePill(tabIo, if (currentTab == 2) accent else muted, if (currentTab == 2) Color.WHITE else normal)

            filterRow.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
            searchBox.visibility = if (currentTab == 2) View.GONE else View.VISIBLE

            fAll.text = if (allIndexed.isNotEmpty()) "ВСЕ (${allIndexed.size})" else "ВСЕ строки"
            fMod.text = "Правки (${overridesByKey.size})"
            stylePill(fPopular, if (editorFilter == 0) accent else muted, if (editorFilter == 0) Color.WHITE else normal)
            stylePill(fAll, if (editorFilter == 1) accent else muted, if (editorFilter == 1) Color.WHITE else normal)
            stylePill(fMod, if (editorFilter == 2) accent else muted, if (editorFilter == 2) Color.WHITE else normal)

            searchBox.hint = if (currentTab == 0) {
                "Поиск языка (Сталкер, Котики, Мемы, 18+...)"
            } else {
                "Поиск по ЛЮБОЙ фразе (напр. в сети, удалить, архив...)"
            }
        }

        fun openEditPhraseDialog(key: String, resId: Int, hintLabel: String) {
            val orig = getOriginalString(key, resId)
            val currentOverride = overridesByKey[key].orEmpty()

            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val p = AndroidUtilities.dp(18f)
                setPadding(p, AndroidUtilities.dp(8f), p, AndroidUtilities.dp(4f))
            }
            val info = TextView(ctx).apply {
                text = "Ключ: $key\nОригинал: «$orig»" +
                    (if (hintLabel.isNotEmpty()) "\nОписание: $hintLabel" else "") +
                    (if (orig.contains("%")) "\n⚠ Сохраняй подстановки вроде %1\$s!" else "")
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            }
            box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f))

            val edit = EditTextBoldCursor(ctx).apply {
                hint = orig
                setText(currentOverride.ifEmpty { orig })
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
                setSelectAllOnFocus(true)
            }
            box.addView(edit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            AlertDialog.Builder(ctx)
                .setTitle("Изменить фразу")
                .setView(box)
                .setPositiveButton("Сохранить") { _, _ ->
                    val newVal = edit.text?.toString().orEmpty()
                    if (newVal == orig || newVal.isEmpty()) {
                        setOverride(key, "")
                    } else {
                        setOverride(key, newVal)
                    }
                    updateChrome()
                    rebuildList()
                    toast("Сохранено! Изменения действуют сразу.")
                }
                .setNeutralButton("Сбросить") { _, _ ->
                    setOverride(key, "")
                    updateChrome()
                    rebuildList()
                    toast("Строка сброшена")
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show()
        }

        rebuildList = {
            listContainer.removeAllViews()
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            when (currentTab) {
                0 -> {
                    // Instant offline presets first
                    if (q.isEmpty()) {
                        listContainer.addView(createCatHeader(ctx, "Мгновенные пресеты MediaTok (офлайн, в 1 тап)"))
                        for ((presetName, map) in PRESETS) {
                            listContainer.addView(createRow(
                                ctx = ctx,
                                title = presetName,
                                subtitle = "Онлайн → «${map["Online"]}» · Офлайн → «${map["Lately"]}» · ${map.size} фраз",
                                accentHighlight = false,
                                onClick = {
                                    applyPreset(map)
                                    updateChrome()
                                    toast("Пресет «$presetName» применён! (${map.size} фраз)")
                                    promptQuickRestart(ctx)
                                }
                            ))
                        }
                        if (overridesByKey.isNotEmpty()) {
                            listContainer.addView(createRow(
                                ctx = ctx,
                                title = "Сбросить все правки MediaTok (${overridesByKey.size})",
                                subtitle = "Вернуть стандартный язык",
                                accentHighlight = false,
                                onClick = {
                                    clearAllOverrides()
                                    updateChrome()
                                    rebuildList()
                                    toast("Все правки языка сброшены")
                                }
                            ))
                        }
                    }

                    // 62 live public Telegram packs
                    var lastCat = ""
                    for (pack in PUBLIC_PACKS) {
                        if (q.isNotEmpty() &&
                            !pack.title.lowercase(Locale.ROOT).contains(q) &&
                            !pack.desc.lowercase(Locale.ROOT).contains(q) &&
                            !pack.category.lowercase(Locale.ROOT).contains(q) &&
                            !pack.slug.lowercase(Locale.ROOT).contains(q)
                        ) continue
                        if (pack.category != lastCat) {
                            lastCat = pack.category
                            listContainer.addView(createCatHeader(ctx, "Telegram-паки: $lastCat"))
                        }
                        listContainer.addView(createRow(
                            ctx = ctx,
                            title = pack.title,
                            subtitle = "${pack.desc}  (t.me/setlanguage/${pack.slug})",
                            accentHighlight = false,
                            onClick = {
                                Browser.openUrl(ctx, "tg://setlanguage?lang=${pack.slug}")
                            }
                        ))
                    }
                }

                1 -> {
                    if (allIndexed.isEmpty()) {
                        listContainer.addView(createCatHeader(ctx, "Индексация всех ~5500 строк клиента..."))
                        Utilities.globalQueue.postRunnable {
                            val loaded = ensureAllEntriesIndexed()
                            AndroidUtilities.runOnUIThread {
                                allIndexed = loaded
                                updateChrome()
                                rebuildList()
                            }
                        }
                    } else {
                        val cap = 120
                        var shown = 0
                        if (editorFilter == 0 && q.isEmpty()) {
                            listContainer.addView(createCatHeader(ctx, "Самые частые фразы (или переключи на «ВСЕ» / ищи выше)"))
                            for ((key, label) in POPULAR_KEYS) {
                                val resId = nameToResId[key] ?: 0
                                val orig = getOriginalString(key, resId)
                                val custom = overridesByKey[key]
                                listContainer.addView(createRow(
                                    ctx = ctx,
                                    title = "$label  [$key]" + if (custom != null) "  ✎" else "",
                                    subtitle = if (custom != null) "$orig  →  $custom" else orig,
                                    accentHighlight = custom != null,
                                    onClick = { openEditPhraseDialog(key, resId, label) }
                                ))
                                shown++
                            }
                        } else if (editorFilter == 2) {
                            listContainer.addView(createCatHeader(ctx, "Изменённые строки (${overridesByKey.size})"))
                            for ((key, custom) in overridesByKey.toSortedMap()) {
                                val resId = nameToResId[key] ?: 0
                                val orig = getOriginalString(key, resId)
                                if (q.isNotEmpty() &&
                                    !key.lowercase(Locale.ROOT).contains(q) &&
                                    !orig.lowercase(Locale.ROOT).contains(q) &&
                                    !custom.lowercase(Locale.ROOT).contains(q)
                                ) continue
                                listContainer.addView(createRow(
                                    ctx = ctx,
                                    title = "$key  ✎",
                                    subtitle = "$orig  →  $custom",
                                    accentHighlight = true,
                                    onClick = { openEditPhraseDialog(key, resId, "") }
                                ))
                                shown++
                                if (shown >= cap) break
                            }
                            if (shown == 0) {
                                listContainer.addView(createCatHeader(ctx, "Пока нет изменённых строк — выбери пресет или отредактируй любую фразу"))
                            }
                        } else {
                            // Full-language search across ALL ~5500+ strings!
                            val headerMsg = if (q.isEmpty()) {
                                "ВСЕ ${allIndexed.size} строк клиента (показаны первые $cap — введи слово в поиск сверху!)"
                            } else {
                                "Поиск «$q» по всем ${allIndexed.size} строкам клиента:"
                            }
                            listContainer.addView(createCatHeader(ctx, headerMsg))
                            for (entry in allIndexed) {
                                val custom = overridesByKey[entry.key]
                                val keyMatch = q.isEmpty() || entry.key.lowercase(Locale.ROOT).contains(q)
                                val orig = if (keyMatch || q.length >= 2) getOriginalString(entry.key, entry.resId) else ""
                                val textMatch = q.isEmpty() || keyMatch ||
                                    orig.lowercase(Locale.ROOT).contains(q) ||
                                    (custom != null && custom.lowercase(Locale.ROOT).contains(q))
                                if (!textMatch) continue
                                listContainer.addView(createRow(
                                    ctx = ctx,
                                    title = entry.key + if (custom != null) "  ✎" else "",
                                    subtitle = if (custom != null) "$orig  →  $custom" else orig,
                                    accentHighlight = custom != null,
                                    onClick = { openEditPhraseDialog(entry.key, entry.resId, "") }
                                ))
                                shown++
                                if (shown >= cap) {
                                    listContainer.addView(createCatHeader(ctx, "Показаны первые $cap совпадений — уточните поиск"))
                                    break
                                }
                            }
                        }
                    }
                }

                2 -> {
                    listContainer.addView(createCatHeader(ctx, "Экспорт и импорт своего языка (.mtlang JSON)"))
                    listContainer.addView(createRow(
                        ctx = ctx,
                        title = "Скопировать мой язык в буфер (${overridesByKey.size} фраз)",
                        subtitle = "Можно отправить другу — он вставит у себя через «Импорт»",
                        accentHighlight = false,
                        onClick = {
                            AndroidUtilities.addToClipboard(exportJson())
                            toast("Язык скопирован в буфер (${overridesByKey.size} фраз)")
                        }
                    ))
                    listContainer.addView(createRow(
                        ctx = ctx,
                        title = "Импортировать язык из буфера обмена",
                        subtitle = "Вставит JSON-словарь MediaTok и сразу применит все замены",
                        accentHighlight = false,
                        onClick = {
                            try {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                                if (clip.isBlank()) {
                                    toast("Буфер обмена пуст")
                                } else {
                                    val n = importJson(clip)
                                    updateChrome()
                                    toast("Импортировано $n фраз!")
                                    promptQuickRestart(ctx)
                                }
                            } catch (e: Throwable) {
                                toast("В буфере неверный JSON языка: ${e.message}")
                            }
                        }
                    ))
                    listContainer.addView(createRow(
                        ctx = ctx,
                        title = "Сбросить все правки языка",
                        subtitle = "Очищает все ${overridesByKey.size} изменённых фраз",
                        accentHighlight = false,
                        onClick = {
                            clearAllOverrides()
                            updateChrome()
                            rebuildList()
                            toast("Язык сброшен к оригиналу")
                        }
                    ))
                }
            }
        }

        tabCatalog.setOnClickListener {
            currentTab = 0; updateChrome(); rebuildList()
        }
        tabEditor.setOnClickListener {
            currentTab = 1; updateChrome(); rebuildList()
        }
        tabIo.setOnClickListener {
            currentTab = 2; updateChrome(); rebuildList()
        }
        fPopular.setOnClickListener {
            editorFilter = 0; updateChrome(); rebuildList()
        }
        fAll.setOnClickListener {
            editorFilter = 1; updateChrome(); rebuildList()
        }
        fMod.setOnClickListener {
            editorFilter = 2; updateChrome(); rebuildList()
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString().orEmpty()
                if (txt != searchQuery) {
                    searchQuery = txt
                    rebuildList()
                }
            }
        })

        // Pre-index all ~5500 strings in background so Editor tab is instant
        if (allIndexed.isEmpty()) {
            Utilities.globalQueue.postRunnable {
                val loaded = ensureAllEntriesIndexed()
                AndroidUtilities.runOnUIThread {
                    allIndexed = loaded
                    updateChrome()
                    if (currentTab == 1) rebuildList()
                }
            }
        }

        updateChrome()
        rebuildList()

        AlertDialog.Builder(ctx)
            .setTitle("Языки и редактор фраз MediaTok")
            .setView(root)
            .setPositiveButton(LocaleController.getString(R.string.Close), null)
            .show()
    }

    private fun promptQuickRestart(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("Обновить весь интерфейс?")
            .setMessage("Новые фразы уже активны! Перезапустить MediaTok сейчас, чтобы обновились все открытые экраны и меню?")
            .setPositiveButton("Перезапустить") { _, _ -> AppRestartHelper.triggerRebirth() }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun createPill(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(5f), AndroidUtilities.dp(6f), AndroidUtilities.dp(5f))
        }
    }

    private fun stylePill(tv: TextView, bg: Int, fg: Int) {
        tv.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = AndroidUtilities.dp(9f).toFloat()
        }
        tv.setTextColor(fg)
    }

    private fun createCatHeader(ctx: Context, title: String): TextView {
        return TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
            setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f))
        }
    }

    private fun createRow(
        ctx: Context,
        title: String,
        subtitle: String,
        accentHighlight: Boolean,
        onClick: () -> Unit
    ): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padH = AndroidUtilities.dp(10f)
            val padV = AndroidUtilities.dp(7f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(9f).toFloat()
                if (accentHighlight) {
                    val accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton)
                    setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    setStroke(AndroidUtilities.dp(1.2f), accent)
                } else {
                    setColor(Color.argb(14, 128, 128, 128))
                }
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        val t1 = TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        val t2 = TextView(ctx).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        box.addView(t1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        box.addView(t2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(2.5f), 0, AndroidUtilities.dp(2.5f))
            addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
    }

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            val ctx = ApplicationLoader.applicationContext ?: return@runOnUIThread
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
