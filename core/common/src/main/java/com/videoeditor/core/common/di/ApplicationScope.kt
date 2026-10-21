package com.videoeditor.core.common.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the application-lifetime coroutine scope.
 *
 * Work launched here outlives any single screen: a ViewModel's `viewModelScope`
 * is cancelled the moment its screen is destroyed, which can cut a save short
 * mid-write. Persistence and other must-complete work uses this scope instead,
 * so an edit committed just before the editor closes still reaches disk.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

/**
 * Provides the application-lifetime scope. A [SupervisorJob] keeps one failed
 * child from cancelling unrelated saves.
 */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object ApplicationScopeModule {

    @dagger.Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
}
