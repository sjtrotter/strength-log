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
    val Watch: ImageVector by lazy {
        ImageVector.Builder(
            name = "Watch",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 1.5f); horizontalLineTo(13f); lineTo(14f, 5f)
                moveTo(7f, 18.5f); horizontalLineTo(13f); lineTo(14f, 15f)
                moveTo(6f, 5f); horizontalLineTo(14f); quadraticBezierTo(16f, 5f, 16f, 7f)
                verticalLineTo(13f); quadraticBezierTo(16f, 15f, 14f, 15f)
                horizontalLineTo(6f); quadraticBezierTo(4f, 15f, 4f, 13f)
                verticalLineTo(7f); quadraticBezierTo(4f, 5f, 6f, 5f)
                close()
            }
        }.build()
    }

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
