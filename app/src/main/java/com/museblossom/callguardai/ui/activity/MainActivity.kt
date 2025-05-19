package com.museblossom.callguardai.ui.activity

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.viewpager.widget.ViewPager
import com.denzcoskun.imageslider.ImageSlider
import com.denzcoskun.imageslider.constants.ScaleTypes
import com.denzcoskun.imageslider.models.SlideModel
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.ActivityMainBinding
import com.museblossom.callguardai.databinding.PermissionDialogBinding
import com.museblossom.callguardai.ui.viewmodel.MainViewModel
import com.museblossom.callguardai.util.etc.MyAccessibilityService
import com.museblossom.callguardai.util.recorder.Recorder

import com.museblossom.deepvoice.util.AudioSource
import com.orhanobut.dialogplus.DialogPlus
import com.orhanobut.dialogplus.ViewHolder

class MainActivity : AppCompatActivity() {
    private lateinit var recorder: Recorder
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var dialogPlus: DialogPlus? = null
    private lateinit var viewPager: ViewPager
    private var isPause = false
    private var currentIndex = 0

    private var audioRecord: AudioRecord? = null
    private var lastText: String = ""
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT


    override fun onResume() {
        Log.i("시점 확인", "리줌,메인")
        if (isPause) {
            checkServicePermisson()
        }
//
        super.onResume()
    }

    override fun onPause() {
        Log.i("시점 확인", "퍼즈,메인")
        isPause = true
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i("시점 확인", "크리,메인")
        binding =
            ActivityMainBinding.inflate(LayoutInflater.from(this)).also { setContentView(it.root) }

        viewModel.isServicePermission.observe(this, Observer { value ->
            // value가 변경될 때마다 호출됩니다.
            if (value == false) {
                binding.serviceOnText.text = "앱 서비스 \n동작안함!"
                Log.i("시점 확인", "권한 확인")
                showAccessibilityDialog(this@MainActivity)
            } else {
                dialogPlus.let {
                    dialogPlus?.dismiss()
                }
                binding.serviceOnText.text = "앱 서비스\n정상작동중!"
            }
        })

        checkServicePermisson()
        Recorder.setSavedAudioSource(this@MainActivity, AudioSource.VOICE_RECOGNITION)

        val deviceInfo =
            "${Build.MODEL};${Build.BRAND};${Build.DISPLAY};${Build.DEVICE};${Build.BOARD};${Build.HARDWARE};${Build.MANUFACTURER};${Build.ID}" +
                    ";${Build.PRODUCT};${Build.VERSION.RELEASE};${Build.VERSION.SDK_INT};${Build.VERSION.INCREMENTAL};${Build.VERSION.CODENAME}"
        Log.d("디바이스 정보", deviceInfo)
    }

    private fun requestAccessibilityPermission() {
        var intent = Intent("com.samsung.accessibility.installed_service")
        if (intent.resolveActivity(packageManager) == null) {
            intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        val extraFragmentArgKey = ":settings:fragment_args_key"
        val extraShowFragmentArguments = ":settings:show_fragment_args"
        val bundle = Bundle()
        val showArgs: String = "${packageName}/${MyAccessibilityService::class.java.name}"
        bundle.putString(extraFragmentArgKey, showArgs)
        intent.putExtra(extraFragmentArgKey, showArgs)
        intent.putExtra(extraShowFragmentArguments, bundle)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY))
        }
    }

    companion object {
        @SuppressLint("MissingPermission")
        @JvmStatic
        fun dialPhone(context: Context, phone: String) {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
        }

        @JvmStatic
        fun getAppDeclaredPermissions(context: Context): Array<out String>? {
            val pm = context.packageManager
            try {
                val packageInfo =
                    pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                return packageInfo.requestedPermissions ?: return null
            } catch (ignored: PackageManager.NameNotFoundException) {
                //we should always find current app
            }
            throw RuntimeException("cannot find current app?!")
        }
    }

    fun excludeFromBatteryOptimization(context: Context) {
        // Android 6.0 (Marshmallow) 이상에서 배터리 최적화 제외 가능
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = context.packageName
            val intent = Intent()
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            Log.e("확인", "배터리 최적화")
            // 앱이 이미 배터리 최적화에서 제외되어 있는지 확인
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e("확인", "배터리 최적화11")
                    Toast.makeText(context, "배터리 최적화 설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("확인", "배터리 최적화 Ok")
                Toast.makeText(context, "앱이 이미 배터리 최적화에서 제외되어 있습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e("확인", "배터리 최적화22")
            Toast.makeText(context, "Android 6.0 이상에서만 지원됩니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
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

    private fun openAccessibilitySettings(context: Context) {
        var intent = Intent("com.samsung.accessibility.installed_service")
        if (intent.resolveActivity(packageManager) == null) {
            intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                Uri.parse("package:$packageName"))
        }
        val extraFragmentArgKey = ":settings:fragment_args_key"
        val extraShowFragmentArguments = ":settings:show_fragment_args"
        val bundle = Bundle()
        val showArgs: String = "${packageName}/${MyAccessibilityService::class.java.name}"
        bundle.putString(extraFragmentArgKey, showArgs)
        intent.putExtra(extraFragmentArgKey, showArgs)
        intent.putExtra(extraShowFragmentArguments, bundle)
        try {
            Log.i("진입 확인","진입1")
            startActivity(intent)
        } catch (e: Exception) {
            Log.i("진입 확인","진입2 : $e")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY))
        }
    }

    private fun checkServicePermisson() {
        Log.e(
            "권한 확인 메인",
            "${
                isAccessibilityServiceEnabled(
                    applicationContext,
                    MyAccessibilityService::class.java
                )
            }"
        )
        if (!isAccessibilityServiceEnabled(
                applicationContext,
                MyAccessibilityService::class.java
            )
        ) {
            viewModel.setBoolean(false)
        } else {
            viewModel.setBoolean(true)
        }
    }

    private fun showAccessibilityDialog(context: Context) {
        if(dialogPlus != null){
            dialogPlus = null
            Log.i("위치 ","됨? 다이얼로그 있")
        }else{
            Log.i("위치 ","됨? 다이얼로그 없")
        }

        val customView = PermissionDialogBinding.inflate(layoutInflater)
        val viewHolder = ViewHolder(customView.root)

        val originalStatusBarColor = window.statusBarColor
        window.statusBarColor = ContextCompat.getColor(this, R.color.dialogplus_black_overlay)

        dialogPlus = DialogPlus.newDialog(this@MainActivity)
            .setContentBackgroundResource(R.drawable.dialog_round)
            .setContentHolder(viewHolder)
            .setCancelable(false)
            .setInAnimation(R.anim.dialog_slide_up_fade_in)
            .setOnDismissListener {
                window.statusBarColor = originalStatusBarColor
            }
            .setExpanded(false)
            .create()

        dialogPlus?.show()

        val imageList = ArrayList<SlideModel>() // Create image list

        imageList.add(SlideModel(R.drawable.accessbillity1))
        imageList.add(SlideModel(R.drawable.accessbillity2))

        var imageSlider = customView.tutorialImage

        viewPager = ImageSlider::class.java.getDeclaredField("viewPager").let { field ->
            field.isAccessible = true
            field.get(imageSlider) as ViewPager
        }

        imageSlider.setImageList(imageList, ScaleTypes.CENTER_CROP)

        customView.movePermissionBtn.setOnClickListener {
            currentIndex++
            if(customView.movePermissionBtn.text.equals("이동하기")){
                openAccessibilitySettings(context)
            }
            if (currentIndex >= imageList.lastIndex) {
                viewPager.currentItem = currentIndex
                customView.movePermissionBtn.text = "이동하기"
                return@setOnClickListener
            }else{
                viewPager.currentItem = currentIndex
            }
        }
    }
}
