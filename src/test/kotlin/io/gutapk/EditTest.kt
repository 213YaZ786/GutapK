package io.gutapk

import io.gutapk.core.edit.Edit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The themed icon edit works on the text APKEditor decodes, so it is tested
// on that text directly, in the layout APKEditor 1.4.9 writes.
class EditTest {
    private val adaptive = """
        <?xml version='1.0' encoding='utf-8' ?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
          <background android:drawable="@color/ic_launcher_background" />
          <foreground android:drawable="@drawable/ic_launcher_foreground" />
        </adaptive-icon>
    """.trimIndent()

    @Test
    fun addsAMonochromeLayerFromTheForeground() {
        val out = Edit.withMonochrome(adaptive)
        val expected = """
            <?xml version='1.0' encoding='utf-8' ?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@color/ic_launcher_background" />
              <foreground android:drawable="@drawable/ic_launcher_foreground" />
              <monochrome android:drawable="@drawable/ic_launcher_foreground" />
            </adaptive-icon>
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun leavesAThemedIconAlone() {
        val themed = Edit.withMonochrome(adaptive)!!
        assertEquals(themed, Edit.withMonochrome(themed))
    }

    @Test
    fun skipsAnInlineForeground() {
        val inline = """
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <foreground>
                <inset android:drawable="@drawable/logo" android:inset="16dp" />
              </foreground>
            </adaptive-icon>
        """.trimIndent()
        assertNull(Edit.withMonochrome(inline))
    }
}
