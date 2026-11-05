package cloud.trotter.log.strength.ui.backup

import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.transfer.csv.CsvImportPreview
import cloud.trotter.log.strength.transfer.csv.PreviewSession
import cloud.trotter.log.strength.transfer.csv.PreviewSet
import cloud.trotter.log.strength.transfer.csv.UnmatchedExerciseName

internal fun csvPreviewFixture() = CsvImportPreview(
    sessions = listOf(
        PreviewSession(
            dayTitle = "Push Day",
            completedAt = 0L,
            sets = listOf(
                PreviewSet("Bench Press", "bench_press", 0, 185.0, 8),
                PreviewSet("Cable Hack Squat", null, 0, 135.0, 10),
            ),
        ),
    ),
    unmatchedNames = listOf(
        UnmatchedExerciseName("Cable Hack Squat", MovementPattern.SQUAT_BILATERAL),
    ),
)
