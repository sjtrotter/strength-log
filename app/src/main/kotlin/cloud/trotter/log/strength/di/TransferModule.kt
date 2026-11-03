package cloud.trotter.log.strength.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import cloud.trotter.log.strength.data.HistoryRepository
import cloud.trotter.log.strength.data.CatalogRepository
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.transfer.csv.CsvHistoryService
import javax.inject.Singleton

/**
 * The `:transfer` object graph (brief D5): both A2 export/import cores are
 * plain constructors over [BackupRepository] and [HistoryRepository], so all this module does is
 * hand Hilt the wiring. `:transfer` itself stays framework-free — no Hilt
 * inside that module.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    @Provides
    @Singleton
    fun backupService(repository: BackupRepository, journal: RestoreJournal): BackupService =
        BackupService(repository, journal)

    @Provides
    @Singleton
    fun csvHistoryService(repository: HistoryRepository, catalog: CatalogRepository): CsvHistoryService =
        CsvHistoryService(repository, catalog = catalog)
}
