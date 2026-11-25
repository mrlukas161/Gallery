package org.fossify.gallery.spherecore

import java.io.File

/** Nastavenie skladania. */
class StitchOptions(
    /** Šírka plnej panorámy 360° v pixeloch (výška = polovica). Zaokrúhli sa na násobok 64. */
    val outWidth: Int = 8192,
    /** Doladiť orientácie zo senzorov podľa obsahu záberov (zhody príznakov). */
    val refine: Boolean = true,
    /** Vyrovnať jas/farbu medzi zábermi. */
    val gainCompensation: Boolean = true,
    /** Hľadať švy mimo rozdielov (paralaxa, pohyb); false = švy len podľa geometrie. */
    val seamOptimization: Boolean = true,
    /** Počet pásiem viacpásmového prelínania; 0 = jednoduché plynulé prelínanie. */
    val blendBands: Int = 5,
    val jpegQuality: Int = 92,
    /** Orezať úplne nepokryté riadky hore a dole (čiastočná panoráma s GPano CroppedArea). */
    val cropUncovered: Boolean = true,
    /** Priebeh: názov fázy (pre používateľa po slovensky rieši volajúci podľa kľúča) a podiel 0..1 celku. */
    val onProgress: (stage: String, fraction: Float) -> Unit = { _, _ -> },
    val isCancelled: () -> Boolean = { false },
    /** Kam zapísať diagnostiku (stitch_log.txt, voliteľne medzivýsledky); null = nikam. */
    val debugDir: File? = null,
)

class StitchResult(
    val outFile: File,
    /** Rozmery uloženého obrázka (po prípadnom orezaní). */
    val width: Int,
    val height: Int,
    /** Rozmery plnej panorámy a poloha výrezu v nej (pre XMP GPano). */
    val fullWidth: Int,
    val fullHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val framesUsed: Int,
    /** Koľko záberov sa podarilo doladiť podľa obsahu (ostatné ostali podľa senzorov). */
    val framesRefined: Int,
    /** Výsledné ohnisko v pixeloch plného záberu (po doladení). */
    val focalPx: Double,
    /** Stredná chyba zhôd po doladení, v stupňoch; NaN ak sa nedolaďovalo. */
    val rmsErrorDeg: Double,
    /** Podiel gule pokrytý aspoň jedným záberom (0..1). */
    val coverage: Double,
    val warnings: List<String>,
)

class StitchCancelledException : RuntimeException("cancelled")

/** Fázy priebehu hlásené cez [StitchOptions.onProgress]. */
object StitchStage {
    const val LOAD = "load"
    const val MATCH = "match"
    const val OPTIMIZE = "optimize"
    const val EXPOSURE = "exposure"
    const val SEAMS = "seams"
    const val BLEND = "blend"
    const val SAVE = "save"
}

/**
 * Zloženie relácie do ekvidištančnej panorámy. Volajúci musí mať načítanú natívnu knižnicu OpenCV
 * (Android: System.loadLibrary("opencv_java4"), PC: nu.pattern.OpenCV.loadLocally()).
 * Výstupný JPEG už obsahuje XMP GPano (cez [XmpGPano]).
 */
object PanoStitcher {
    fun stitch(session: SphereSession, sessionDir: File, outFile: File, options: StitchOptions = StitchOptions()): StitchResult {
        return PanoStitcherImpl(session, sessionDir, outFile, options).run()
    }
}
