package com.videoeditor.core.database.di

import android.content.Context
import androidx.room.Room
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.dao.ExportJobDao
import com.videoeditor.core.database.dao.ProjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VideoEditorDatabase =
        Room.databaseBuilder(
            context,
            VideoEditorDatabase::class.java,
            VideoEditorDatabase.NAME,
        ).build()

    @Provides
    fun provideProjectDao(database: VideoEditorDatabase): ProjectDao = database.projectDao()

    @Provides
    fun provideExportJobDao(database: VideoEditorDatabase): ExportJobDao = database.exportJobDao()
}
