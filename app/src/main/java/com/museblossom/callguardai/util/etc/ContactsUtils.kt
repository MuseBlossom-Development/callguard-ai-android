package com.museblossom.callguardai.util.etc

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.museblossom.callguardai.R

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
    fun getContactName(
        context: Context,
        phoneNumber: String,
    ): String? {
        try {
            // 권한 확인
            if (!hasContactsPermission(context)) {
                Log.w(TAG, context.getString(R.string.contact_permission_required))
                return null
            }

            // 다양한 형태의 전화번호 생성
            val numbers = generatePhoneNumberVariants(phoneNumber)
            Log.d(
                TAG,
                "${context.getString(
                    R.string.log_call_record_query_result,
                )}: 원본=$phoneNumber, 변형=${numbers.joinToString()}",
            )

            val cursor =
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null,
                    null,
                    null,
                )

            cursor?.use {
                while (it.moveToNext()) {
                    val nameIndex =
                        it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex =
                        it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    if (nameIndex >= 0 && numberIndex >= 0) {
                        val name = it.getString(nameIndex)
                        val storedNumber = it.getString(numberIndex)

                        // 저장된 번호와 조회하는 번호의 모든 변형을 비교
                        val storedVariants = generatePhoneNumberVariants(storedNumber)

                        for (searchNum in numbers) {
                            for (storedNum in storedVariants) {
                                if (searchNum == storedNum) {
                                    Log.d(
                                        TAG,
                                        "${context.getString(
                                            R.string.contact_found,
                                        )}: $phoneNumber -> $name (매칭: $searchNum = $storedNum)",
                                    )
                                    return name
                                }
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "${context.getString(R.string.contact_not_found)}: $phoneNumber")
            return null
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.contact_query_error), e)
            return null
        }
    }

    /**
     * 전화번호 표시용 포맷팅
     * @param phoneNumber 원본 전화번호
     * @param contactName 연락처 이름 (없으면 null)
     * @return 표시용 문자열
     */
    fun formatPhoneDisplay(
        phoneNumber: String,
        contactName: String?,
    ): String {
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
            val cursor =
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
            cursor?.close()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, context.getString(R.string.contact_permission_required), e)
            false
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.contact_query_error), e)
            false
        }
    }

    /**
     * 전화번호의 다양한 변형 생성
     * @param phoneNumber 원본 전화번호
     * @return 변형된 전화번호 목록
     */
    private fun generatePhoneNumberVariants(phoneNumber: String): List<String> {
        val variants = mutableSetOf<String>()

        // 원본 추가
        variants.add(phoneNumber)

        // 모든 특수문자 제거
        val numbersOnly = phoneNumber.replace(Regex("[^0-9]"), "")
        variants.add(numbersOnly)

        // 한국 번호 처리
        if (numbersOnly.startsWith("010")) {
            // 010-1234-5678 형태
            if (numbersOnly.length == 11) {
                variants.add(
                    "${numbersOnly.substring(0, 3)}-${
                        numbersOnly.substring(
                            3,
                            7,
                        )
                    }-${numbersOnly.substring(7)}",
                )
                variants.add(
                    "${numbersOnly.substring(0, 3)} ${
                        numbersOnly.substring(
                            3,
                            7,
                        )
                    } ${numbersOnly.substring(7)}",
                )
            }
        }

        // +82 형태 처리
        if (numbersOnly.startsWith("82") && numbersOnly.length == 12) {
            val withoutCountryCode = "0" + numbersOnly.substring(2)
            variants.add(withoutCountryCode)
            variants.add("+82 " + numbersOnly.substring(2))
            variants.add("+82-" + numbersOnly.substring(2))
        }

        // 앞의 0 제거/추가
        if (numbersOnly.startsWith("0")) {
            variants.add(numbersOnly.substring(1))
        } else if (!numbersOnly.startsWith("0") && numbersOnly.length == 10) {
            variants.add("0$numbersOnly")
        }

        return variants.toList()
    }
}
