package com.museblossom.callguardai.di

import android.content.Context
import com.museblossom.callguardai.data.network.CallGuardApiService
import com.museblossom.callguardai.data.repository.AudioAnalysisRepositoryImpl
import com.museblossom.callguardai.data.repository.CallGuardRepositoryImpl
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.domain.repository.CallGuardRepositoryInterface
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import com.museblossom.callguardai.domain.usecase.CallGuardUseCase
import com.museblossom.callguardai.util.retrofit.sevice.Mp3UploadService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * DI 모듈 - Hilt를 사용한 의존성 주입
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .connectTimeout(60L, TimeUnit.SECONDS)
            .readTimeout(60L, TimeUnit.SECONDS)
            .writeTimeout(60L, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    @Named("CallGuardApi")
    fun provideCallGuardRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://1026a630-d34e-4090-b2db-894bcb256a4d.mock.pstmn.io/") // Mock 서버 URL (개발용)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("DeepVoiceApi")
    fun provideDeepVoiceRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://1026a630-d34e-4090-b2db-894bcb256a4d.mock.pstmn.io/") // Mock 서버 URL (개발용)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideCallGuardApiService(@Named("CallGuardApi") retrofit: Retrofit): CallGuardApiService {
        return retrofit.create(CallGuardApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideMp3UploadService(@Named("DeepVoiceApi") retrofit: Retrofit): Mp3UploadService {
        return retrofit.create(Mp3UploadService::class.java)
    }

    // === Repository Providers ===

    @Provides
    @Singleton
    fun provideAudioAnalysisRepository(
        @ApplicationContext context: Context,
        callGuardRepository: CallGuardRepositoryInterface
    ): AudioAnalysisRepositoryInterface {
        return AudioAnalysisRepositoryImpl(context, callGuardRepository)
    }

    @Provides
    @Singleton
    fun provideCallGuardRepository(
        apiService: CallGuardApiService,
        @ApplicationContext context: Context
    ): CallGuardRepositoryInterface {
        return CallGuardRepositoryImpl(apiService, context)
    }

    // === UseCase Providers ===

    @Provides
    @Singleton
    fun provideAnalyzeAudioUseCase(
        repository: AudioAnalysisRepositoryInterface
    ): AnalyzeAudioUseCase {
        return AnalyzeAudioUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideCallGuardUseCase(
        repository: CallGuardRepositoryInterface
    ): CallGuardUseCase {
        return CallGuardUseCase(repository)
    }
}
