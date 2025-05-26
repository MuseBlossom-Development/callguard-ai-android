package com.museblossom.callguardai.di

import android.content.Context
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import com.museblossom.callguardai.repository.AudioAnalysisRepository
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
    fun provideAudioAnalysisRepository(
        @ApplicationContext context: Context
    ): AudioAnalysisRepository {
        return AudioAnalysisRepository.getInstance(context)
    }

    @Provides
    fun provideAnalyzeAudioUseCase(
        repository: AudioAnalysisRepository
    ): AnalyzeAudioUseCase {
        return AnalyzeAudioUseCase(repository)
    }
}
