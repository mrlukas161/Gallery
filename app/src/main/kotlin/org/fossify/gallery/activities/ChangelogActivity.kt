package org.fossify.gallery.activities

import android.os.Bundle
import androidx.core.graphics.ColorUtils
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.databinding.ActivityChangelogBinding

// História zmien priamo v aplikácii (assets/changelog.html) — Lukáš si podľa nej odkontroluje funkcie.
// Stránka si tmavý režim rieši cez prefers-color-scheme, čo WebView berie zo SYSTÉMU telefónu, nie
// z témy Galérie+. Preto jej farby premaľujeme podľa témy appky (pozadie, text, akcent).
class ChangelogActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityChangelogBinding::inflate)

    // farby, s ktorými je stránka práve načítaná — pri zmene témy v Nastaveniach sa načíta znova
    private var appliedColors: Triple<Int, Int, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.changelogAppbar),
            padBottomSystem = listOf(binding.changelogWeb)
        )
        binding.changelogWeb.settings.apply {
            javaScriptEnabled = false
            textZoom = 100
        }
        loadChangelog()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.changelogAppbar, NavigationIcon.Arrow)
        if (appliedColors != currentColors()) {
            loadChangelog()
        }
    }

    private fun currentColors() = Triple(getProperBackgroundColor(), getProperTextColor(), getProperPrimaryColor())

    private fun loadChangelog() {
        val colors = currentColors()
        appliedColors = colors
        val (background, text, primary) = colors
        binding.changelogWeb.setBackgroundColor(background)

        val html = try {
            assets.open("changelog.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Throwable) {
            null
        }

        if (html == null) {
            binding.changelogWeb.loadUrl("file:///android_asset/changelog.html")
        } else {
            binding.changelogWeb.loadDataWithBaseURL(
                "file:///android_asset/", withAppTheme(html, background, text, primary), "text/html", "utf-8", null,
            )
        }
    }

    // Pred koniec <head> pridá premenné farieb z témy appky — majú prednosť pred prefers-color-scheme
    // (rovnaká špecifickosť, ale neskôr v poradí).
    private fun withAppTheme(html: String, backgroundColor: Int, textColor: Int, primaryColor: Int): String {
        // výpočty kontrastu vyžadujú nepriesvitné farby
        val opaque = 0xFF000000.toInt()
        val background = backgroundColor or opaque
        val text = textColor or opaque
        val primary = primaryColor or opaque
        val isDark = ColorUtils.calculateLuminance(background) < 0.5
        val muted = ColorUtils.blendARGB(text, background, 0.4f)
        val card = ColorUtils.blendARGB(background, text, 0.07f)
        val line = ColorUtils.blendARGB(background, text, 0.18f)
        // akcent z témy, ale len ak je na pozadí dobre čitateľný (inak by zanikol nadpis verzie)
        val accent = if (ColorUtils.calculateContrast(primary, background) >= 3.0) primary else text
        val added = if (isDark) 0xFF3ECF8E.toInt() else 0xFF1F8A5B.toInt()
        val fixed = if (isDark) 0xFFFFB25D.toInt() else 0xFFB25E00.toInt()

        val css = "<style>:root{color-scheme:${if (isDark) "dark" else "light"};" +
            "--bg:${hex(background)};--fg:${hex(text)};--mut:${hex(muted)};--card:${hex(card)};" +
            "--line:${hex(line)};--accent:${hex(accent)};--new:${hex(added)};--fix:${hex(fixed)};" +
            "--test:${hex(accent)};}</style>"

        val headEnd = html.indexOf("</head>", ignoreCase = true)
        return if (headEnd >= 0) {
            html.substring(0, headEnd) + css + html.substring(headEnd)
        } else {
            css + html
        }
    }

    private fun hex(color: Int) = String.format("#%06X", 0xFFFFFF and color)
}
