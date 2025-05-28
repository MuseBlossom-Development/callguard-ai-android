package com.museblossom.callguardai.ui.activity

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.ActivityAlarmOffBinding
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.util.recorder.Recorder
import dagger.hilt.android.AndroidEntryPoint
import io.github.tonnyl.spark.Spark
import javax.inject.Inject

@AndroidEntryPoint
class AlarmOffActivity : AppCompatActivity() {

    @Inject
    lateinit var audioAnalysisRepository: AudioAnalysisRepositoryInterface

    private lateinit var binding: ActivityAlarmOffBinding
    private lateinit var recorder: Recorder
    private lateinit var notificationManager: NotificationManager
    private lateinit var spark: Spark
    private var aiPercent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAlarmOffBinding.inflate(layoutInflater).apply {
            setContentView(root)
        }

        spark = Spark.Builder()
            .setView(binding.root)
            .setDuration(2000)
            .setAnimList(R.drawable.custom_anim_list)
            .build()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        recorder = Recorder(this, {}, { b: Boolean, i: Int ->
            Log.e("확인", "퍼센트 : $i")
            aiPercent = i
        }, audioAnalysisRepository)

        binding.warningTextView.text = "AI가 생성한 딥보이스 확률\n$aiPercent% 입니다"

        binding.alarmOffBtn.setOnClickListener {
            recorder.offVibrate(applicationContext)
            notificationManager.cancel(R.string.channel_id__deep_voice_detect)  // 특정 ID의 알림 취소\
            spark.stopAnimation()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        spark.startAnimation()
    }
}
