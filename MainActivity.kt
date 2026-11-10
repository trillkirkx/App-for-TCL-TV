package com.example.mytvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val p by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private var inputsSec: Pair<LinearLayout, LinearLayout>? = null
    private var weatherView: TextView? = null
    private var weatherText = ""
    private var weatherAt = 0L
    private val tim by lazy { getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager }

    private val refreshTask = Runnable { refreshInputs() }
    private fun scheduleInputs() { ui.removeCallbacks(refreshTask); ui.postDelayed(refreshTask, 500) }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = scheduleInputs()
    }
    private val tvCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = scheduleInputs()
    }

    // ---------- настройки ----------
    private fun flag(k: String) = p.getBoolean(k, true)
    private fun list(k: String) = p.getString(k, "")!!.split("\n").filter { it.isNotBlank() }
    private fun saveList(k: String, l: List<String>) = p.edit().putString(k, l.joinToString("\n")).apply()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- жизненный цикл ----------
    override fun onResume() { super.onResume(); build() }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL); addDataScheme("file")
        }
        registerReceiver(mediaReceiver, f)
        try { tim?.registerCallback(tvCallback, ui) } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(mediaReceiver)
        try { tim?.unregisterCallback(tvCallback) } catch (_: Exception) {}
    }

    override fun onBackPressed() { /* мы домашний экран — не закрываемся */ }

    // ---------- интерфейс ----------
    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        root.addView(header())

        inputsSec = null
        if (flag("inputs")) {
            val s = section("Подключено сейчас"); inputsSec = s; root.addView(s.first); refreshInputs()
        }
        if (flag("fav")) {
            val (box, row) = section("Избранное")
            val favs = list("fav")
            if (favs.isEmpty()) row.addView(TextView(this).apply {
                text = "Долгое нажатие на приложение — добавить сюда. Ссылки: Настройки → Добавить ссылку"
                setTextColor(0xFF9AA3B2.toInt()); textSize = 14f
            })
            favs.forEach { e -> (if (e.startsWith("app:")) appTile(e.drop(4)) else linkTile(e))?.let { row.addView(it) } }
            root.addView(box)
        }
        if (flag("recent")) {
            val rec = list("recent").mapNotNull { appTile(it) }
            if (rec.isNotEmpty()) { val (box, row) = section("Недавние"); rec.forEach { row.addView(it) }; root.addView(box) }
        }
        if (flag("apps")) {
            val (box, row) = section("Все приложения")
            launchPackages().forEach { pkg -> appTile(pkg)?.let { row.addView(it) } }
            root.addView(box)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0F1217.toInt()); isVerticalScrollBarEnabled = false; addView(root)
        })
    }

    private fun header(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(8))
        }
        h.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 56f; setTextColor(Color.WHITE)
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, 0, 0) }
        col.addView(TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"; textSize = 20f; setTextColor(Color.WHITE)
        })
        weatherView = null
        if (flag("weather")) {
            weatherView = TextView(this).apply { textSize = 18f; setTextColor(0xFF9AA3B2.toInt()); text = weatherText }
            col.addView(weatherView); fetchWeather()
        }
        h.addView(col)
        h.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        h.addView(tile("Настройки", null, "⚙", { settings() }))
        return h
    }

    private fun section(title: String): Pair<LinearLayout, LinearLayout> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        box.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(0xFF9AA3B2.toInt()); setPadding(dp(56), 0, 0, dp(4))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(40), dp(10), dp(40), dp(10))
            clipChildren = false; clipToPadding = false
        }
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(row)
        })
        return box to row
    }

    private fun box(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(12).toFloat() }

    private fun tile(label: String, icon: Drawable?, badge: String?, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), box(0xFF3D5AFE.toInt()))
                addState(intArrayOf(), box(0xFF1E232C.toInt()))
            }
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
            setOnFocusChangeListener { v, f ->
                val s = if (f) 1.08f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
            layoutParams = LinearLayout.LayoutParams(dp(176), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(8), 0, dp(8), 0) }
        }
        if (icon != null) t.addView(ImageView(this).apply {
            setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(90))
        }) else t.addView(TextView(this).apply {
            text = badge; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(90))
        })
        t.addView(TextView(this).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        return t
    }

    // ---------- приложения ----------
    private fun launchPackages(): List<String> {
        val pm = packageManager
        fun q(cat: String) = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
        return (q(Intent.CATEGORY_LEANBACK_LAUNCHER) + q(Intent.CATEGORY_LAUNCHER))
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { it.activityInfo.packageName }
    }

    private fun appTile(pkg: String): View? = try {
        val pm = packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        val icon = ai.loadBanner(pm) ?: ai.loadIcon(pm)
        tile(ai.loadLabel(pm).toString(), icon, null, { launch(pkg) }, { appMenu(pkg) })
    } catch (e: Exception) { null }

    private fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast("Не удалось запустить"); return }
        saveList("recent", (listOf(pkg) + list("recent").filter { it != pkg }).take(10))
        try { startActivity(i) } catch (e: Exception) { toast("Ошибка запуска") }
    }

    private fun appMenu(pkg: String) {
        val key = "app:$pkg"
        val inFav = list("fav").contains(key)
        AlertDialog.Builder(this)
            .setItems(arrayOf(if (inFav) "Убрать из избранного" else "В избранное", "Сведения о приложении")) { _, w ->
                if (w == 0) { saveList("fav", if (inFav) list("fav") - key else list("fav") + key); build() }
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }.show()
    }

    // ---------- ссылки ----------
    private fun linkTile(e: String): View {
        val parts = e.removePrefix("url:").split("::", limit = 2)
        val url = parts.getOrElse(1) { "" }
        return tile(parts[0], null, "WWW", {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (x: Exception) { toast("Нет приложения для ссылки") }
        }, {
            AlertDialog.Builder(this).setItems(arrayOf("Удалить из избранного")) { _, _ ->
                saveList("fav", list("fav") - e); build()
            }.show()
        })
    }

    private fun linkDialog() {
        val name = EditText(this).apply { hint = "Название"; setSingleLine() }
        val url = EditText(this).apply { hint = "Адрес, например youtube.com"; setSingleLine() }
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(name); addView(url)
        }
        AlertDialog.Builder(this).setTitle("Добавить ссылку").setView(lay)
            .setPositiveButton("Добавить") { _, _ ->
                var u = url.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.startsWith("http")) u = "https://$u"
                val n = name.text.toString().trim().ifEmpty { u.removePrefix("https://") }
                saveList("fav", list("fav") + "url:$n::$u"); build()
            }.setNegativeButton("Отмена", null).show()
    }

    // ---------- HDMI / USB (только если подключено) ----------
    private fun refreshInputs() {
        val (box, row) = inputsSec ?: return
        row.removeAllViews()
        try {
            val m = tim
            m?.inputList?.forEach { info ->
                if (info.type == TvInputInfo.TYPE_HDMI && m.getInputState(info.id) == TvInputManager.INPUT_STATE_CONNECTED) {
                    val label = info.loadLabel(this)?.toString() ?: "HDMI"
                    row.addView(tile(label, null, "HDMI", {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id)))
                        } catch (e: Exception) { toast("Не удалось открыть вход") }
                    }))
                }
            }
        } catch (_: Exception) {}
        try {
            getSystemService(StorageManager::class.java).storageVolumes
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .forEach { v ->
                    row.addView(tile(v.getDescription(this), null, "USB", {
                        try {
                            startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
                        } catch (e: Exception) { toast("Нет файлового менеджера") }
                    }))
                }
        } catch (_: Exception) {}
        box.visibility = if (row.childCount == 0) View.GONE else View.VISIBLE
    }

    // ---------- погода ----------
    private fun http(u: String): String = (URL(u).openConnection() as HttpURLConnection).run {
        connectTimeout = 8000; readTimeout = 8000; inputStream.bufferedReader().readText()
    }

    private fun desc(c: Int) = when (c) {
        0 -> "ясно"; 1, 2, 3 -> "облачно"; 45, 48 -> "туман"
        in 51..67, in 80..82 -> "дождь"; in 71..77, 85, 86 -> "снег"; in 95..99 -> "гроза"; else -> ""
    }

    private fun fetchWeather(force: Boolean = false) {
        val lat = p.getString("lat", "")!!
        val lon = p.getString("lon", "")!!
        if (lat.isEmpty()) { weatherText = "Укажите город: Настройки → Город"; weatherView?.text = weatherText; return }
        if (!force && System.currentTimeMillis() - weatherAt < 15 * 60_000) return
        weatherAt = System.currentTimeMillis()
        thread {
            try {
                val j = JSONObject(http("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")).getJSONObject("current")
                val txt = "${p.getString("city", "")}: ${Math.round(j.getDouble("temperature_2m"))}°C, ${desc(j.getInt("weather_code"))}"
                runOnUiThread { weatherText = txt; weatherView?.text = txt }
            } catch (e: Exception) { weatherAt = 0 }
        }
    }

    private fun cityDialog() {
        val et = EditText(this).apply { hint = "Например: Москва"; setText(p.getString("city", "")); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Город для погоды").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                thread {
                    try {
                        val r = JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" + URLEncoder.encode(name, "UTF-8")))
                            .getJSONArray("results").getJSONObject(0)
                        p.edit().putString("city", r.getString("name"))
                            .putString("lat", r.getDouble("latitude").toString())
                            .putString("lon", r.getDouble("longitude").toString()).apply()
                        runOnUiThread { fetchWeather(true) }
                    } catch (e: Exception) { runOnUiThread { toast("Город не найден") } }
                }
            }.setNegativeButton("Отмена", null).show()
    }

    // ---------- окно настроек ----------
    private fun settings() {
        val names = arrayOf("Погода", "Подключённые HDMI/USB", "Избранное", "Недавние", "Все приложения")
        val keys = arrayOf("weather", "inputs", "fav", "recent", "apps")
        val st = BooleanArray(keys.size) { flag(keys[it]) }
        AlertDialog.Builder(this).setTitle("Что показывать")
            .setMultiChoiceItems(names, st) { _, i, c -> st[i] = c }
            .setPositiveButton("Готово") { _, _ ->
                p.edit().also { e -> keys.forEachIndexed { i, k -> e.putBoolean(k, st[i]) }; e.apply() }
                build()
            }
            .setNeutralButton("Город") { _, _ -> cityDialog() }
            .setNegativeButton("Добавить ссылку") { _, _ -> linkDialog() }
            .show()
    }
}
