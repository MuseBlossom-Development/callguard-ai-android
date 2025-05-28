package com.museblossom.callguardai.util.retrofit.sevice

import com.museblossom.callguardai.domain.model.ServerResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface Mp3UploadService {
    //    @Multipart
//    @Headers("Accept: application/json")
//    @POST("detect") // 서버의 파일 업로드 엔드포인트
//    fun uploadMp3(
//        @Part file: MultipartBody.Part
//    ): Call<ServerResponse> // 서버 응답에 따라 Void 대신 적절한 타입을 사용
    @Headers(
        "Accept: application/json",
        "Content-Type: audio/mpeg"
    )
    @POST("api/v2/voices/detect") // 엔드포인트 설정
    fun uploadMp3(
        @Body file: RequestBody // 바이너리 데이터 전송
    ): Call<ServerResponse>
}
