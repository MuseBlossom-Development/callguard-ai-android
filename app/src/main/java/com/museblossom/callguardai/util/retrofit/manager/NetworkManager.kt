package com.museblossom.callguardai.util.retrofit.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.GsonBuilder
import com.museblossom.callguardai.Model.ServerResponse
import com.museblossom.callguardai.util.retrofit.sevice.Mp3UploadService
import okhttp3.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor

/**
 * 모든 네트워크 통신을 관리하는 중앙 집중식 네트워크 매니저
 */
class NetworkManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkManager"
        private const val BASE_URL = "https://dev-deepvoice.museblossom.com/"
        private const val CONNECT_TIMEOUT = 60L
        private const val READ_TIMEOUT = 60L
        private const val WRITE_TIMEOUT = 60L
        
        @Volatile
        private var INSTANCE: NetworkManager? = null
        
        fun getInstance(context: Context): NetworkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // HTTP 로깅 인터셉터
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    // 네트워크 인터셉터 (연결 상태 확인)
    private val networkInterceptor = Interceptor { chain ->
        if (!isNetworkAvailable()) {
            throw IOException("네트워크 연결이 필요합니다")
        }
        chain.proceed(chain.request())
    }
    
    // 헤더 인터셉터
    private val headerInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()
            .addHeader("User-Agent", "CallGuardAI-Android")
            .addHeader("Accept", "application/json")
        
        chain.proceed(requestBuilder.build())
    }
    
    // OkHttp 클라이언트
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor(networkInterceptor)
        .addInterceptor(headerInterceptor)
        .addInterceptor(loggingInterceptor)
        .retryOnConnectionFailure(true)
        .build()
    
    // Gson 설정
    private val gson = GsonBuilder()
        .setLenient()
        .setDateFormat("yyyy-MM-dd HH:mm:ss")
        .create()
    
    // Retrofit 인스턴스
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    // 서비스 인터페이스들
    private val mp3UploadService: Mp3UploadService = retrofit.create(Mp3UploadService::class.java)
    
    /**
     * 네트워크 연결 상태 확인
     */
    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    
    /**
     * MP3 파일 업로드 (비동기)
     */
    suspend fun uploadMp3File(file: File): Result<ServerResponse> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext Result.failure(Exception("파일이 존재하지 않습니다: ${file.path}"))
            }
            
            val requestBody = RequestBody.create(
                "audio/mpeg".toMediaType(),
                file
            )

            Log.d(TAG, "MP3 파일 업로드 시작: ${file.name}, 크기: ${file.length()} bytes")

            val response = mp3UploadService.uploadMp3(requestBody).execute()

            if (response.isSuccessful) {
                val serverResponse = response.body()
                if (serverResponse != null) {
                    Log.d(TAG, "MP3 업로드 성공: AI 확률 = ${serverResponse.body.ai_probability}%")
                    Result.success(serverResponse)
                } else {
                    Log.e(TAG, "MP3 업로드 실패: 응답 본문이 null")
                    Result.failure(Exception("서버 응답이 비어있습니다"))
                }
            } else {
                Log.e(TAG, "MP3 업로드 실패: ${response.code()} - ${response.message()}")
                Result.failure(Exception("서버 오류: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "MP3 업로드 중 오류 발생", e)
            Result.failure(e)
        }
    }

    /**
     * MP3 파일 업로드 (콜백 방식)
     */
    fun uploadMp3FileCallback(
        file: File,
        onSuccess: (ServerResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isNetworkAvailable()) {
            onError("네트워크 연결을 확인해주세요")
            return
        }

        if (!file.exists()) {
            onError("파일이 존재하지 않습니다: ${file.path}")
            return
        }

        val requestBody = RequestBody.create(
            "audio/mpeg".toMediaType(),
            file
        )

        Log.d(TAG, "MP3 파일 업로드 시작 (콜백): ${file.name}")

        mp3UploadService.uploadMp3(requestBody).enqueue(object : Callback<ServerResponse> {
            override fun onResponse(call: Call<ServerResponse>, response: Response<ServerResponse>) {
                if (response.isSuccessful) {
                    response.body()?.let { serverResponse ->
                        Log.d(TAG, "MP3 업로드 성공 (콜백): AI 확률 = ${serverResponse.body.ai_probability}%")
                        onSuccess(serverResponse)
                    } ?: run {
                        Log.e(TAG, "MP3 업로드 실패 (콜백): 응답 본문이 null")
                        onError("서버 응답이 비어있습니다")
                    }
                } else {
                    val errorMsg = "서버 오류: ${response.code()} - ${response.message()}"
                    Log.e(TAG, "MP3 업로드 실패 (콜백): $errorMsg")
                    onError(errorMsg)
                }
            }

            override fun onFailure(call: Call<ServerResponse>, t: Throwable) {
                val errorMsg = "네트워크 오류: ${t.message}"
                Log.e(TAG, "MP3 업로드 실패 (콜백): $errorMsg", t)
                onError(errorMsg)
            }
        })
    }

    /**
     * 바이트 배열로 MP3 업로드
     */
    suspend fun uploadMp3Bytes(audioBytes: ByteArray): Result<ServerResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = RequestBody.create(
                "audio/mpeg".toMediaType(),
                audioBytes
            )
            
            Log.d(TAG, "MP3 바이트 배열 업로드 시작: 크기 = ${audioBytes.size} bytes")
            
            val response = mp3UploadService.uploadMp3(requestBody).execute()
            
            if (response.isSuccessful) {
                val serverResponse = response.body()
                if (serverResponse != null) {
                    Log.d(TAG, "MP3 바이트 업로드 성공: AI 확률 = ${serverResponse.body.ai_probability}%")
                    Result.success(serverResponse)
                } else {
                    Result.failure(Exception("서버 응답이 비어있습니다"))
                }
            } else {
                Result.failure(Exception("서버 오류: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "MP3 바이트 업로드 중 오류 발생", e)
            Result.failure(e)
        }
    }
    
    /**
     * 모든 진행 중인 네트워크 요청 취소
     */
    fun cancelAllRequests() {
        okHttpClient.dispatcher.cancelAll()
        Log.d(TAG, "모든 네트워크 요청이 취소되었습니다")
    }
    
    /**
     * 네트워크 매니저 리소스 해제
     */
    fun release() {
        cancelAllRequests()
        okHttpClient.connectionPool.evictAll()
        INSTANCE = null
        Log.d(TAG, "NetworkManager 리소스가 해제되었습니다")
    }
    
    /**
     * 네트워크 상태 리스너 인터페이스
     */
    interface NetworkStateListener {
        fun onNetworkAvailable()
        fun onNetworkLost()
    }
    
    /**
     * 업로드 진행률 리스너 인터페이스
     */
    interface UploadProgressListener {
        fun onProgressUpdate(percentage: Int)
        fun onUploadComplete()
        fun onUploadError(error: String)
    }
}
