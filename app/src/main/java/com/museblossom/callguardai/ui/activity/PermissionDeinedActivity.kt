package com.museblossom.deepvoice.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.denzcoskun.imageslider.constants.ScaleTypes
import com.denzcoskun.imageslider.models.SlideModel
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.ActivityPermissionDeinedBinding
import com.museblossom.callguardai.databinding.PermissionEtcDialogBinding
import com.museblossom.callguardai.ui.activity.SplashActivity
import com.orhanobut.dialogplus.DialogPlus
import com.orhanobut.dialogplus.ViewHolder

class PermissionDeinedActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPermissionDeinedBinding
    private lateinit var dialogPlus: DialogPlus
    private lateinit var customView: PermissionEtcDialogBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionDeinedBinding.inflate(layoutInflater).apply {
            setContentView(root)
        }
//        binding.permissionBtn.setOnClickListener {
////            moveToSplashActivity()
//
//        }
        showEtcPermissionDialog(this@PermissionDeinedActivity)
    }

    private fun moveToSplashActivity() {
        var intent = Intent(this@PermissionDeinedActivity, SplashActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun showEtcPermissionDialog(context: Context) {
//        AlertDialog.Builder(context)
//            .setTitle("접근성 권한 요청")
//            .setMessage("앱이 원활하게 작동하려면 접근성 권한이 필요합니다. 설정 화면으로 이동하여 권한을 활성화해 주세요.")
//            .setCancelable(false)
//            .setPositiveButton("설정으로 이동") { _, _ ->
//                openAccessibilitySettings(context)
//            }
////            .setNegativeButton("취소", null)
//            .show()

//        val customView = LayoutInflater.from(this@SplashActivity).inflate(R.layout.permission_dialog,null)
        val customView = PermissionEtcDialogBinding.inflate(layoutInflater)
        val viewHolder = ViewHolder(customView.root)

        val originalStatusBarColor = window.statusBarColor
        window.statusBarColor = ContextCompat.getColor(this, R.color.dialogplus_black_overlay)

        dialogPlus = DialogPlus.newDialog(this@PermissionDeinedActivity)
            .setContentBackgroundResource(R
                .drawable.dialog_round)
            .setContentHolder(viewHolder)
            .setCancelable(false)
            .setExpanded(false)
            .setOnDismissListener {
                window.statusBarColor = originalStatusBarColor
            }
            .create()

        dialogPlus.show()

        val imageList = ArrayList<SlideModel>() // Create image list

        imageList.add(SlideModel(R.drawable.etc_permission))


        var imageSlider = customView.tutorialImage

        imageSlider.setImageList(imageList, ScaleTypes.CENTER_CROP)

        customView.movePermissionBtn.setOnClickListener {
           moveToEtcPermissionActivity()
        }
    }
    private fun moveToEtcPermissionActivity() {
        var intent = Intent(this@PermissionDeinedActivity, EtcPermissonActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }
}