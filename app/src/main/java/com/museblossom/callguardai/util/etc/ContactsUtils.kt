package com.museblossom.callguardai.util.etc

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/**
 * 연락처 조회 유틸리티
 * 책임: 전화번호부에서 연락처 정보 조회
 */
object ContactsUtils {
    private const val TAG = "ContactsUtils"

    /**
     * 전화번호로 연락처 이름 조회
     * @param context 컨텍스트
     * @param phoneNumber 조회할 전화번호
     * @return 연락처 이름 (없으면 null)
     */
    fun getContactName(context: Context, phoneNumber: String): String? {
        try {
            // 전화번호 정규화 (공백, 하이픈 제거)
            val normalizedNumber = phoneNumber.replace(Regex("[\\s-()]"), "")

            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
                arrayOf(phoneNumber, normalizedNumber),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex =
                        it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = it.getString(nameIndex)
                        Log.d(TAG, "연락처 찾음: $phoneNumber -> $name")
                        return name
                    }
                }
            }

            Log.d(TAG, "연락처를 찾을 수 없음: $phoneNumber")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "연락처 조회 중 오류", e)
            return null
        }
    }

    /**
     * 전화번호 표시용 포맷팅
     * @param phoneNumber 원본 전화번호
     * @param contactName 연락처 이름 (없으면 null)
     * @return 표시용 문자열
     */
    fun formatPhoneDisplay(phoneNumber: String, contactName: String?): String {
        return if (contactName != null) {
            "$contactName ($phoneNumber)"
        } else {
            phoneNumber
        }
    }

    /**
     * 권한 확인용 전화번호 접근 가능 여부
     * @param context 컨텍스트
     * @return 접근 가능 여부
     */
    fun hasContactsPermission(context: Context): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.close()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "연락처 권한이 없음", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "연락처 권한 확인 중 오류", e)
            false
        }
    }
}