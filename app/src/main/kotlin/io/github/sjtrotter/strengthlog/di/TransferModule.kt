package io.github.sjtrotter.strengthlog.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.transfer.backup.BackupService
import io.github.sjtrotter.strengthlog.transfer.csv.CsvHistoryService
import javax.inject.Singleton

/**
 * The `:transfer` object graph (brief D5): both A2 export/import cores are
 * plain constructors over [TrackerRepository], so all this module does is
 * hand Hilt the wiring. `:transfer` itself stays framework-free — no Hilt
 * inside that module.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    @Provides
    @Singleton
    fun backupService(repository: TrackerRepository): BackupService = BackupService(repository)

    @Provides
    @Singleton
    fun csvHistoryService(repository: TrackerRepository): CsvHistoryService = CsvHistoryService(repository)
}
