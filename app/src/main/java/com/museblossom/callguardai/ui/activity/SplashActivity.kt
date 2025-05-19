package com.museblossom.callguardai.ui.activity

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.denzcoskun.imageslider.ImageSlider
import com.denzcoskun.imageslider.constants.ScaleTypes
import com.denzcoskun.imageslider.models.SlideModel
import com.mackhartley.roundedprogressbar.RoundedProgressBar
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.ActivitySplashBinding
import com.museblossom.callguardai.databinding.PermissionOverlayDialogBinding
import com.museblossom.callguardai.ui.viewmodel.SplashViewModel
import com.orhanobut.dialogplus.DialogPlus
import com.orhanobut.dialogplus.ViewHolder
import kotlinx.coroutines.launch
import render.animations.Render
import java.io.File


class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    private lateinit var render: Render
    private lateinit var sliderView: ImageSlider
    private lateinit var dialogPlus: DialogPlus
    private lateinit var customView: PermissionOverlayDialogBinding
    private lateinit var viewHolder: ViewHolder
    private lateinit var progressBar: RoundedProgressBar
    private lateinit var statusTextView: TextView
    private  var permissionsGranted = true
    private var isPause = false
    private val viewModel: SplashViewModel by viewModels()


    override fun onResume() {
        super.onResume()
//        Log.i("시점 확인", "리줌")
//        if (!permissionsGranted && !isPause) {
//            checkAndRequestPermissions()
//        }
    }

    override fun onPause() {
        super.onPause()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater).apply {
            setContentView(root)
        }
//        binding.logo.alpha = 0f
//        binding.logoText.alpha = 0f
        initValue()


    }

    private fun initValue() {
        render = Render(this@SplashActivity)

        val logoImage = binding.logo
        val logoText = binding.logoText
        statusTextView = binding.tvStatus
        progressBar = binding.progressBar

        fadeInViewsSequentially(logoImage, logoText, 1000L)
    }

    private fun initView() {

    }

    private fun fadeInViewsSequentially(view1: View, view2: View, duration: Long) {
        // 첫 번째 뷰의 alpha 값을 0으로 설정 (투명)
        view1.alpha = 0f
        view2.alpha = 0f

        // 첫 번째 뷰의 alpha 값을 1로 애니메이션
        val fadeIn1 = ObjectAnimator.ofFloat(view1, "alpha", 0f, 1f).apply {
            this.duration = duration
        }

        // 첫 번째 애니메이션이 끝난 후 두 번째 뷰의 애니메이션을 시작
        fadeIn1.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {

            }

            override fun onAnimationEnd(animation: Animator) {
                // 첫 번째 뷰가 페이드인 후 두 번째 뷰의 애니메이션 시작
                val fadeIn2 = ObjectAnimator.ofFloat(view2, "alpha", 0f, 1f).apply {
                    this.duration = duration
                }

                fadeIn2.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {

                    }

                    override fun onAnimationEnd(animation: Animator) {
                        Log.d("다운확인 ","애니메이션 끝남")
                        if(!checkModelExists()){
                            downloadModel()
                        }else{
                            progressBar.visibility = View.VISIBLE
                            progressBar.setProgressPercentage(100.0)
                            statusTextView.text = "준비 완료"
//                        navigateToMain()
                            dialogSetting()
                            if (!Settings.canDrawOverlays(applicationContext)) {
                                showOverlayPermissionDialog(applicationContext)
                            }else{
                                moveToEtcPermissionActivity()
                            }
                        }
//                        else {
//                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                                val permissionsToRequest =
//                                    getAppDeclaredPermissions(applicationContext)
//                                if (permissionsToRequest != null)
//                                    requestPermissions(permissionsToRequest, 0)
//                            }
//                        }
                    }
                    override fun onAnimationCancel(animation: Animator) {

                    }

                    override fun onAnimationRepeat(animation: Animator) {

                    }

                })
                fadeIn2.start()
            }

            override fun onAnimationCancel(animation: Animator) {

            }

            override fun onAnimationRepeat(animation: Animator) {
            }
        })
        fadeIn1.start()
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(
                    ComponentName(context, service).flattenToString(),
                    ignoreCase = true
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun showOverlayPermissionDialog(context: Context) {

        dialogPlus.show()

        Log.e("확인", "다이얼로그 닫음4")
        val imageList = ArrayList<SlideModel>() // Create image list
        imageList.add(SlideModel(R.drawable.overlay_permission))

        var imageSlider = customView.tutorialImage
        imageSlider.setImageList(imageList, ScaleTypes.CENTER_CROP)

        customView.movePermissionBtn.setOnClickListener {
            checkOverlayPermission() //todo 어레이 마지막 버튼시
        }

    }
    private fun moveToMainActivity() {
        var intent = Intent(this@SplashActivity, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }
    private fun moveToEtcPermissionActivity() {
        var intent = Intent(this@SplashActivity, EtcPermissonActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }


    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            activityResultLauncher.launch(intent)
        } else {
//            showOverlay()
        }
    }


    private val activityResultLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Log.e("확인", "오버레이 권한 있음")
            if (dialogPlus.isShowing) {
                Log.e("확인", "다이얼로그 닫음1")
                dialogPlus.dismiss()
                moveToEtcPermissionActivity()
            }
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                Log.e("확인", "다이얼로그 각종권한")
//                val permissionsToRequest = getAppDeclaredPermissions(this)
//                if (permissionsToRequest != null)
//                    requestPermissions(permissionsToRequest, 0)
//            }
        } else {
            Log.e("확인", "오버레이 권한 없음")
            if (dialogPlus.isShowing) {
                Log.e("확인", "다이얼로그 닫음2")
//                dialogPlus.dismiss()
                showOverlayPermissionDialog(applicationContext)
            }
        }
    }

    private fun dialogSetting() {
        customView = PermissionOverlayDialogBinding.inflate(layoutInflater)
        viewHolder = ViewHolder(customView.root)

        val originalStatusBarColor = window.statusBarColor
        window.statusBarColor = ContextCompat.getColor(this,R.color.dialogplus_black_overlay)

        dialogPlus = DialogPlus.newDialog(this@SplashActivity)
            .setContentBackgroundResource(R.drawable.dialog_round)
            .setContentHolder(viewHolder)
            .setCancelable(false)
            .setInAnimation(R.anim.dialog_slide_up_fade_in)
            .setOnDismissListener {
                window.statusBarColor = originalStatusBarColor
            }
            .setExpanded(false)
            .create()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSION_CODE) {
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()

            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.add(permissions[i])
                } else {
                    deniedPermissions.add(permissions[i])
                }
            }
            permissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (!permissionsGranted && !isPause) {
                // 권한이 거부된 경우 다이얼로그 표시
                if (deniedPermissions.size == 1){
                    if (deniedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")) {
                        moveToMainActivity()
                    }
                }else{
                    isPause = true // 다이얼로그가 표시되었음을 표시
                    showEtcPermission(this@SplashActivity)
                }
            } else {
                // 권한이 모두 승인되었을 때 처리할 코드 추가
                Log.d("Permission", "권한이 승인되었습니다.")
                isPause = false // 권한이 승인된 경우 다이얼로그를 다시 표시할 수 있도록 초기화
            }
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
                isPause = true
                startActivity(intent)
            }
//            .setNegativeButton("취소", null)
            .show()
    }

    private fun checkModelExists(): Boolean{
        val ggmlFile = File(filesDir, "ggml-small.bin")
        return if (ggmlFile.exists()) {
            Log.d("다운확인 ","파일있음")
            true
        }else{
            false
        }
    }

    private fun downloadModel(){
        viewModel.ensureGgmlFile()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.progress.collect { pct ->
                    when {
                        pct < 0.0 -> statusTextView.text = "다운로드 실패"
                        pct < 100.0 -> {
                            progressBar.visibility = View.VISIBLE
                            progressBar.setProgressPercentage(pct)
                            statusTextView.text = "다운로드 중: ${"%.1f".format(pct)}%"
                        }
                        else -> {
                            statusTextView.text = "준비 완료"
//                        navigateToMain()
                            dialogSetting()
                            if (!Settings.canDrawOverlays(applicationContext)) {
                                showOverlayPermissionDialog(applicationContext)
                            }else{
                                moveToEtcPermissionActivity()
                            }
                        }
                    }
                }
            }
        }
    }


    companion object {
        private const val REQUEST_PERMISSION_CODE = 0
    }
}