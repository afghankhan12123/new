package com.videoeditor.app

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface AcceptancePreferences {
    fun preferences(): com.videoeditor.core.datastore.UserPreferencesRepository
}

