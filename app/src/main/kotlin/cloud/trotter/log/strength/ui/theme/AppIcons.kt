package cloud.trotter.log.strength.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons that are not available in the app's material-icons-core dependency. */
object AppIcons {
    val SwapHoriz: ImageVector by lazy {
        ImageVector.Builder(
            name = "SwapHoriz",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 7f)
                horizontalLineTo(18f)
                moveTo(15f, 4f)
                lineTo(18f, 7f)
                lineTo(15f, 10f)
                moveTo(18f, 17f)
                horizontalLineTo(6f)
                moveTo(9f, 14f)
                lineTo(6f, 17f)
                lineTo(9f, 20f)
            }
        }.build()
    }
}
