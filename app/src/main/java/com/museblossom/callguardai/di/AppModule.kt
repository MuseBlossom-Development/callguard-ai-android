package com.museblossom.callguardai.di

import android.content.Context
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import com.museblossom.callguardai.repository.AudioAnalysisRepository
import com.museblossom.callguardai.util.retrofit.manager.NetworkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DI 모듈 - Hilt를 사용한 의존성 주입
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNetworkManager(
        @ApplicationContext context: Context
    ): NetworkManager {
        return NetworkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideAudioAnalysisRepository(
        @ApplicationContext context: Context,
        networkManager: NetworkManager
    ): AudioAnalysisRepositoryInterface {
        return AudioAnalysisRepository(context, networkManager)
    }

    @Provides
    @Singleton
    fun provideAnalyzeAudioUseCase(
        repository: AudioAnalysisRepositoryInterface
    ): AnalyzeAudioUseCase {
        return AnalyzeAudioUseCase(repository)
    }
}
