package com.museblossom.callguardai.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import com.museblossom.callguardai.data.model.LoginData
import com.museblossom.callguardai.ui.activity.LoginActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.museblossom.callguardai.domain.repository.CallGuardRepositoryInterface
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat

@AndroidEntryPoint
class TermsAgreementActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TermsAgreementActivity"
    }

    @Inject
    lateinit var callGuardRepository: CallGuardRepositoryInterface
    val auth = FirebaseAuth.getInstance()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 상태바 색상을 흰색으로 설정
        window.statusBarColor = android.graphics.Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            true

        val googleIdToken = intent.getStringExtra("google_id_token") ?: run {
            Log.e(TAG, "구글 ID 토큰이 없습니다.")
            Toast.makeText(this, "인증 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }


        Log.d(TAG, "구글 ID 토큰을 받았습니다. 토큰 길이: ${googleIdToken.length}")

        setContent {
            MaterialTheme {
                TermsAgreementScreen(
                    onAgreementComplete = { agreedTerms ->
                        sendTokenToServer(googleIdToken, agreedTerms)
                    },
                    onBackPressed = {
                        val intent = Intent(this@TermsAgreementActivity, LoginActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                )
            }
        }
    }

    private fun proceedToPermissionScreen() {
        // FCM 토큰 서버 전송
        initializeFCMFromTermsAgreement()

        val intent = Intent(this, EtcPermissonActivity::class.java)
        startActivity(intent)
        finish()
    }

    /**
     * 약관 동의 완료 후 FCM 토큰을 서버로 전송
     */
    private fun initializeFCMFromTermsAgreement() {
        lifecycleScope.launch {
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w("FCM", "FCM 토큰 가져오기 실패", task.exception)
                        return@addOnCompleteListener
                    }

                    val token = task.result
                    Log.d("FCM", "약관 동의 후 FCM 토큰: $token")

                    // 서버로 토큰 전송
                    sendTokenToServer(token)
                }
            } catch (e: Exception) {
                Log.e("FCM", "FCM 토큰 처리 중 오류", e)
            }
        }
    }

    /**
     * FCM 토큰을 서버로 전송
     */
    private fun sendTokenToServer(token: String) {
        lifecycleScope.launch {
            try {
                // Repository를 통해 토큰 전송
                callGuardRepository.updatePushToken(token)
                    .onSuccess {
                        Log.d("FCM", "FCM 토큰 서버 전송 완료")
                    }
                    .onFailure { e ->
                        Log.e("FCM", "FCM 토큰 서버 전송 실패", e)
                    }
            } catch (e: Exception) {
                Log.e("FCM", "FCM 토큰 서버 전송 실패", e)
            }
        }
    }

    private fun sendTokenToServer(googleIdToken: String, agreedTerms: Map<String, Boolean>) {
        Log.i(TAG, "서버로 토큰을 전송합니다.")
        Log.d(TAG, "약관 동의 상태: $agreedTerms")

        lifecycleScope.launch {
            try {
                // 먼저 로그인/회원가입 진행
                val result = callGuardRepository.snsLogin(googleIdToken)

                result.fold(
                    onSuccess = { loginData ->
                        Log.i(TAG, "로그인/회원가입 성공 - 토큰: ${loginData.token}")

                        // 마케팅 동의가 있으면 별도로 업데이트
                        val agreedToMarketing = agreedTerms["marketing"] ?: false
                        val marketingAgreement = if (agreedToMarketing) "Y" else "N"
                        Log.d(TAG, "마케팅 동의 업데이트 중... 값: $marketingAgreement")
                        callGuardRepository.updateMarketingAgreement(marketingAgreement)
                            .onFailure { e ->
                                Log.e(TAG, "마케팅 동의 업데이트 실패", e)
                                // 마케팅 동의 실패는 무시하고 진행
                            }

                        // 서버 회원가입 성공 후 Firebase Auth에 로그인
                        val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
                        auth.signInWithCredential(credential)
                            .addOnSuccessListener { authResult ->
                                Log.d(TAG, "Firebase Auth 로그인 성공 - 사용자: ${authResult.user?.email}")
                                Toast.makeText(
                                    this@TermsAgreementActivity,
                                    "로그인이 완료되었습니다!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                proceedToPermissionScreen()
                            }
                            .addOnFailureListener { firebaseException ->
                                Log.e(TAG, "Firebase Auth 로그인 실패", firebaseException)
                                // Firebase 로그인 실패해도 서버 로그인은 성공했으므로 진행
                                Toast.makeText(
                                    this@TermsAgreementActivity,
                                    "로그인이 완료되었습니다!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                proceedToPermissionScreen()
                            }
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "로그인/회원가입 실패", exception)
                        Toast.makeText(
                            this@TermsAgreementActivity,
                            "로그인 중 오류가 발생했습니다: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "서버 통신 중 오류 발생", e)
                Toast.makeText(
                    this@TermsAgreementActivity,
                    "서버 통신 중 오류가 발생했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun proceedToMain() {
        Log.i(TAG, "메인 화면으로 이동합니다.")
        val intent = Intent(this, SplashActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsAgreementScreen(
    onAgreementComplete: (Map<String, Boolean>) -> Unit,
    onBackPressed: () -> Unit
) {
    var allChecked by remember { mutableStateOf(false) }
    var termsChecked by remember { mutableStateOf(false) }
    var privacyChecked by remember { mutableStateOf(false) }
    var marketingChecked by remember { mutableStateOf(false) }

    LaunchedEffect(termsChecked, privacyChecked) {
        allChecked = termsChecked && privacyChecked
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // TopAppBar with back button
            CenterAlignedTopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { onBackPressed() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = Color.Black
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.White
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Title
                Text(
                    text = "이용 약관 동의",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Subtitle
                Text(
                    text = "필수항목 및 선택항목 약관에 ㄱ동의해 주세요.",
                    fontSize = 16.sp,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                // All agree section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            allChecked = !allChecked
                            termsChecked = allChecked
                            privacyChecked = allChecked
                            marketingChecked = allChecked
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF9F9F9)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (allChecked) Icons.Outlined.CheckCircle
                            else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (allChecked) Color(0xFF2196F3) else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "전체 동의하기",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Individual terms
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Terms of service
                    TermsItemNew(
                        title = "[필수] 서비스 이용약관",
                        checked = termsChecked,
                        onCheckedChange = { termsChecked = it },
                        onDetailClick = { /* TODO: 약관 상세 보기 */ }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Privacy policy
                    TermsItemNew(
                        title = "[필수] 개인정보 처리방침",
                        checked = privacyChecked,
                        onCheckedChange = { privacyChecked = it },
                        onDetailClick = { /* TODO: 개인정보 처리방침 상세 보기 */ }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Marketing agreement
                    TermsItemNew(
                        title = "[선택] 마케팅 정보 수신 동의",
                        checked = marketingChecked,
                        onCheckedChange = { marketingChecked = it },
                        onDetailClick = { /* TODO: 마케팅 약관 상세 보기 */ }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Agree and proceed button
                Button(
                    onClick = {
                        if (termsChecked && privacyChecked) {
                            onAgreementComplete(
                                mapOf(
                                    "terms" to termsChecked,
                                    "privacy" to privacyChecked,
                                    "marketing" to marketingChecked
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = termsChecked && privacyChecked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF41BCD8),
                        disabledContainerColor = Color(0xFFF3F3F3)
                    ),
                    shape = RoundedCornerShape(5.dp)
                ) {
                    Text(
                        text = "동의하고 가입하기",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!allChecked) Color(0xFFB5B5B5)
                        else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun TermsItemNew(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDetailClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = if (checked) Color(0xFF4FC3F7) else Color.Gray.copy(alpha = 0.3f),
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            fontSize = 16.sp,
            color = Color.Black,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = "상세보기",
            tint = Color.Gray,
            modifier = Modifier
                .padding(8.dp)
                .clickable { onDetailClick() }
                .size(24.dp)
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun TermsAgreementScreenPreview() {
    MaterialTheme {
        TermsAgreementScreen(
            onAgreementComplete = {},
            onBackPressed = {}
        )
    }
}
