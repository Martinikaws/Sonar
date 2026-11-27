package app.sonar.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import com.composables.icons.lucide.CaseSensitive
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MicVocal
import com.composables.icons.lucide.Star

internal val emphasizedCaseSensitive by lazy { Lucide.CaseSensitive.withHeavierStroke() }
internal val emphasizedLanguages by lazy { Lucide.Languages.withHeavierStroke() }
internal val emphasizedMicVocal by lazy { Lucide.MicVocal.withHeavierStroke() }
internal val emphasizedStar by lazy { Lucide.Star.withHeavierStroke() }

// These Lucide icons consist of ungrouped stroke paths.
private fun ImageVector.withHeavierStroke(): ImageVector {
    val builder = ImageVector.Builder(name, defaultWidth, defaultHeight, viewportWidth, viewportHeight)
    for (node in root) {
        val path = node as VectorPath
        builder.addPath(
            pathData = path.pathData,
            stroke = path.stroke,
            strokeLineWidth = 3f,
            strokeLineCap = path.strokeLineCap,
            strokeLineJoin = path.strokeLineJoin,
        )
    }
    return builder.build()
}
