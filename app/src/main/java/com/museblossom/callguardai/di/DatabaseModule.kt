package com.museblossom.callguardai.di

import android.content.Context
import androidx.room.Room
import com.museblossom.callguardai.data.database.CallGuardDatabase
import com.museblossom.callguardai.data.model.CallRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 데이터베이스 관련 의존성 주입 모듈
 * 책임: Room 데이터베이스와 DAO 제공
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Room 데이터베이스 제공
     */
    @Provides
    @Singleton
    fun provideCallGuardDatabase(
        @ApplicationContext context: Context
    ): CallGuardDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            CallGuardDatabase::class.java,
            "callguard_database"
        )
            .fallbackToDestructiveMigration() // 개발 단계에서만 사용
            .build()
    }

    /**
     * CallRecordDao 제공
     */
    @Provides
    fun provideCallRecordDao(database: CallGuardDatabase): CallRecordDao {
        return database.callRecordDao()
    }
}