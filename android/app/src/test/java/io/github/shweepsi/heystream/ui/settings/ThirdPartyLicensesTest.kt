package io.github.shweepsi.heystream.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** org.json vient d'Android : Robolectric le fournit. */
@RunWith(AndroidJUnit4::class)
class ThirdPartyLicensesTest {
    @Test
    fun listsEachLibraryThenEachLicenseText() {
        val json = """
            {"libraries": [
              {"uniqueId": "androidx.core:core", "artifactVersion": "1.19.1", "name": "Core",
               "website": "https://developer.android.com", "licenses": ["Apache-2.0"]},
              {"uniqueId": "org.example:nameless", "artifactVersion": "2.0", "licenses": []}
            ],
            "licenses": {"Apache-2.0": {"name": "Apache License 2.0", "content": "Licensed under the Apache License"}}}
        """
        val text = androidLibraries(json)
        assertTrue(text, text.contains("Core 1.19.1 — Apache License 2.0\nhttps://developer.android.com"))
        assertTrue(text, text.contains("org.example:nameless 2.0\n"))
        assertTrue(text, text.contains("Apache License 2.0\n" + "-".repeat(70) + "\n\nLicensed under the Apache License"))
    }
}
