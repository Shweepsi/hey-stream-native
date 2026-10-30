package io.github.shweepsi.heystream.ui.settings

import android.content.Context
import io.github.shweepsi.heystream.R
import org.json.JSONObject

/** Texte d'une licence ou d'une liste de licences, lu hors du fil principal. */
fun License.read(context: Context): String = when (this) {
    License.AndroidLibraries -> context.resources.openRawResource(R.raw.aboutlibraries)
        .bufferedReader().use { androidLibraries(it.readText()) }
    else -> context.assets.open(asset).bufferedReader().use { it.readText() }
}

/**
 * Liste des bibliothèques Android livrées dans l'app et texte de chaque
 * licence, d'après `aboutlibraries.json` (généré à la compilation par le
 * plugin AboutLibraries).
 */
fun androidLibraries(json: String): String {
    val root = JSONObject(json)
    val libraries = root.getJSONArray("libraries")
    val licenses = root.getJSONObject("licenses")
    return buildString {
        for (i in 0 until libraries.length()) {
            val library = libraries.getJSONObject(i)
            val ids = library.optJSONArray("licenses")
            val names = (0 until (ids?.length() ?: 0)).joinToString(", ") { j ->
                val id = ids!!.getString(j)
                licenses.optJSONObject(id)?.optString("name")?.takeIf { it.isNotEmpty() } ?: id
            }
            append(library.optString("name").ifEmpty { library.getString("uniqueId") })
            append(' ').append(library.optString("artifactVersion"))
            if (names.isNotEmpty()) append(" — ").append(names)
            library.optString("website").takeIf { it.isNotEmpty() }?.let { append('\n').append(it) }
            append("\n\n")
        }
        for (id in licenses.keys()) {
            val license = licenses.getJSONObject(id)
            append("\n").append(license.optString("name", id)).append("\n")
            append("-".repeat(70)).append("\n\n")
            append(license.optString("content")).append("\n")
        }
    }
}
