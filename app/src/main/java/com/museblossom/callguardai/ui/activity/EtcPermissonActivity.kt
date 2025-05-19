package com.museblossom.deepvoice.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.normal.TedPermission
import com.museblossom.callguardai.R


class EtcPermissonActivity : AppCompatActivity() {
    private var isRetryPermission = false


    override fun onResume() {
        super.onResume()
        Log.d("Permission", "리줌 퍼미션")
//        if (isRetryPermission) {
//            checkAndRequestPermissions()
//        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_etc_permisson)
        Log.d("Permission", "메인 퍼미션")

        setPermission(permission)
//        checkAndRequestPermissions()

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == EtcPermissonActivity.REQUEST_PERMISSION_CODE) {
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()

            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.add(permissions[i])
                } else {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isEmpty()) {
                Log.d("Permission", "모든 권한을 획득했습니다: $grantedPermissions")
                // 모든 권한이 허용됨, 필요한 후속 작업 수행
            } else {
                Log.d("Permission", "권한 요청 실패: $deniedPermissions")
                if (deniedPermissions.size == 1) {
                    if (deniedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")) {
                        moveToMainActivity()
                    } else {
                        Log.d("Permission", "권한 요청 실패1111: $deniedPermissions")
//                        checkAndRequestPermissions()
                        showEtcPermission(this@EtcPermissonActivity)
                    }
                }    // 거부된 권한 처리, 사용자에게 안내 메시지를 표시하거나 요청 재시도
                else {
                    Log.d("Permission", "권한 요청 실패2222: $deniedPermissions")
                    if (!isRetryPermission) {
                        showEtcPermission(this@EtcPermissonActivity)
                    }
                }
            }
        }
    }

    private val permission = object : PermissionListener {
        override fun onPermissionGranted() {
//            Toast.makeText(this@EtcPermissonActivity, "권한 허가", Toast.LENGTH_SHORT).show()
//            //TODO your task
            moveToMainActivity()
        }

        override fun onPermissionDenied(deniedPermissions: MutableList<String>?) {
//            Toast.makeText(this@EtcPermissonActivity, "권한 거부", Toast.LENGTH_SHORT).show()
            moveToPermissonDeinedActivity()
            Log.d("Permission", "테드_권한 거부 : $deniedPermissions")
            Log.d("Permission", "테드_버전 여부 : ${Build.VERSION.SDK_INT}")
        }

    }

    private fun showEtcPermission(context: Context) {

        AlertDialog.Builder(context)
            .setTitle("권한 요청")
            .setMessage("앱이 원활하게 작동하려면 모든 권한이 필요합니다. 권한을 활성화해 주세요.")
            .setCancelable(false)
            .setPositiveButton("권한 수락하기") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + applicationContext.packageName)
                }
                isRetryPermission = true
                startActivity(intent)
            }
//            .setNegativeButton("취소", null)
            .show()
    }

    private fun moveToMainActivity() {
        var intent = Intent(this@EtcPermissonActivity, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }
    private fun moveToPermissonDeinedActivity() {
        var intent = Intent(this@EtcPermissonActivity, PermissionDeinedActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }


    private fun setPermission(permissionListener: PermissionListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO,
//                    Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE)
                .check()
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU){
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(
                    Manifest.permission.READ_MEDIA_AUDIO,
//                    Manifest.permission.READ_MEDIA_VIDEO,
//                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
                .check()
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2){
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
                .check()
        }else{
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
        }
    }

    companion object {
        private const val REQUEST_PERMISSION_CODE = 0
    }
}