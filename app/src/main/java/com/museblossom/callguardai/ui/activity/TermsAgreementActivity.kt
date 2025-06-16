package com.museblossom.callguardai.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import com.museblossom.callguardai.data.model.CDNUrlData
import com.museblossom.callguardai.data.model.LoginData
import com.museblossom.callguardai.data.model.STTModelData
import com.museblossom.callguardai.domain.repository.CallGuardRepositoryInterface
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import javax.inject.Inject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TermsRepositoryEntryPoint {
    fun callGuardRepository(): CallGuardRepositoryInterface
}

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

        val googleIdToken =
            intent.getStringExtra("google_id_token") ?: run {
                Log.e(TAG, "구글 ID 토큰이 없습니다.")
                Toast.makeText(this, "인증 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

        Log.d(TAG, "구글 ID 토큰을 받았습니다. 토큰 길이: ${googleIdToken.length}")

        setContent {
            MaterialTheme {
                TermsAgreementScreen(
                    callGuardRepository = callGuardRepository,
                    onAgreementComplete = { agreedTerms, onSuccess, onFailure ->
                        sendTokenToServer(googleIdToken, agreedTerms, onSuccess, onFailure)
                    },
                    onBackPressed = {
                        val intent = Intent(this@TermsAgreementActivity, LoginActivity::class.java)
                        startActivity(intent)
                        finish()
                    },
                )
            }
        }
    }

    private fun proceedToPermissionScreen() {
        // FCM 토큰을 백그라운드에서 전송 (화면 전환과 독립적으로)
        sendFCMTokenInBackground()

        val intent = Intent(this, EtcPermissonActivity::class.java)
        startActivity(intent)
        finish()
    }

    /**
     * FCM 토큰을 백그라운드에서 서버로 전송 (GlobalScope 사용)
     */
    private fun sendFCMTokenInBackground() {
        GlobalScope.launch {
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        Log.d("FCM", "약관 동의 후 FCM 토큰: $token")

                        // GlobalScope에서 서버 전송
                        GlobalScope.launch {
                            try {
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
                    } else {
                        Log.w("FCM", "FCM 토큰 가져오기 실패", task.exception)
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM", "FCM 토큰 처리 중 오류", e)
            }
        }
    }

    private fun sendTokenToServer(
        googleIdToken: String,
        agreedTerms: Map<String, Boolean>,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
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
                                    Toast.LENGTH_SHORT,
                                ).show()
                                proceedToPermissionScreen()
                                onSuccess()
                            }
                            .addOnFailureListener { firebaseException ->
                                Log.e(TAG, "Firebase Auth 로그인 실패", firebaseException)
                                // Firebase 로그인 실패해도 서버 로그인은 성공했으므로 진행
                                Toast.makeText(
                                    this@TermsAgreementActivity,
                                    "로그인이 완료되었습니다!",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                proceedToPermissionScreen()
                                onSuccess()
                            }
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "로그인/회원가입 실패", exception)
                        Toast.makeText(
                            this@TermsAgreementActivity,
                            "로그인 중 오류가 발생했습니다: ${exception.message}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        onFailure()
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "서버 통신 중 오류 발생", e)
                Toast.makeText(
                    this@TermsAgreementActivity,
                    "서버 통신 중 오류가 발생했습니다.",
                    Toast.LENGTH_SHORT,
                ).show()
                onFailure()
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
    callGuardRepository: CallGuardRepositoryInterface,
    onAgreementComplete: (Map<String, Boolean>, () -> Unit, () -> Unit) -> Unit,
    onBackPressed: () -> Unit,
) {
    var allChecked by remember { mutableStateOf(false) }
    var termsChecked by remember { mutableStateOf(false) }
    var privacyChecked by remember { mutableStateOf(false) }
    var marketingChecked by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 다이얼로그 상태 추가
    var showTermsDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showMarketingDialog by remember { mutableStateOf(false) }

    LaunchedEffect(termsChecked, privacyChecked) {
        allChecked = termsChecked && privacyChecked
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // TopAppBar with back button
            CenterAlignedTopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { onBackPressed() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = Color.Black,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.White,
                    ),
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
            ) {
                // Title
                Text(
                    text = "이용 약관 동의",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                // Subtitle
                Text(
                    text = "필수항목 및 선택항목 약관에 동의해 주세요.",
                    fontSize = 16.sp,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 32.dp),
                )

                if (isLoading) {
                    // 로딩 UI
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (error != null) {
                    // 에러 UI
                    Text(
                        text = error!!,
                        color = Color.Red,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                } else {
                    // All agree section
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    allChecked = !allChecked
                                    termsChecked = allChecked
                                    privacyChecked = allChecked
                                    marketingChecked = allChecked
                                },
                        colors =
                            CardDefaults.cardColors(
                                containerColor = Color(0xFFF9F9F9),
                            ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector =
                                    if (allChecked) {
                                        Icons.Outlined.CheckCircle
                                    } else {
                                        Icons.Outlined.CheckCircle
                                    },
                                contentDescription = null,
                                tint = if (allChecked) Color(0xFF2196F3) else Color.Gray,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "전체 동의하기",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Individual terms
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        // Terms of service
                        TermsItemNew(
                            title = "[필수] 서비스 이용약관",
                            checked = termsChecked,
                            onCheckedChange = { termsChecked = it },
                            onDetailClick = { showTermsDialog = true },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Privacy policy
                        TermsItemNew(
                            title = "[필수] 개인정보 처리방침",
                            checked = privacyChecked,
                            onCheckedChange = { privacyChecked = it },
                            onDetailClick = { showPrivacyDialog = true },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Marketing agreement
                        TermsItemNew(
                            title = "[선택] 마케팅 정보 수신 동의",
                            checked = marketingChecked,
                            onCheckedChange = { marketingChecked = it },
                            onDetailClick = { showMarketingDialog = true },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Agree and proceed button
                Button(
                    onClick = {
                        if (termsChecked && privacyChecked) {
                            isLoggingIn = true
                            onAgreementComplete(
                                mapOf(
                                    "terms" to termsChecked,
                                    "privacy" to privacyChecked,
                                    "marketing" to marketingChecked,
                                ),
                                {
                                    // 성공 콜백
                                    isLoggingIn = false
                                },
                                {
                                    // 실패 콜백 - 상태 리셋
                                    isLoggingIn = false
                                    allChecked = false
                                    termsChecked = false
                                    privacyChecked = false
                                    marketingChecked = false
                                },
                            )
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    enabled = termsChecked && privacyChecked && !isLoggingIn,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF41BCD8),
                            disabledContainerColor = Color(0xFFB5B5B5),
                        ),
                    shape = RoundedCornerShape(5.dp),
                ) {
                    Text(
                        text = if (isLoggingIn) "로그인 중..." else "동의하고 가입하기",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }

    // 다이얼로그들
    if (showTermsDialog) {
        TermsDetailDialog(
            title = "서비스 이용약관",
            termsType = "terms_of_use",
            onDismiss = { showTermsDialog = false },
            callGuardRepository = callGuardRepository,
        )
    }

    if (showPrivacyDialog) {
        TermsDetailDialog(
            title = "개인정보 처리방침",
            termsType = "privacy_policy",
            onDismiss = { showPrivacyDialog = false },
            callGuardRepository = callGuardRepository,
        )
    }

    if (showMarketingDialog) {
        TermsDetailDialog(
            title = "마케팅 정보 수신 동의",
            termsType = "marketing_consent",
            onDismiss = { showMarketingDialog = false },
            callGuardRepository = callGuardRepository,
        )
    }
}

@Composable
fun TermsItemNew(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDetailClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = if (checked) Color(0xFF4FC3F7) else Color.Gray.copy(alpha = 0.3f),
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            fontSize = 16.sp,
            color = Color.Black,
            modifier = Modifier.weight(1f),
        )

        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = "상세보기",
            tint = Color.Gray,
            modifier =
                Modifier
                    .padding(8.dp)
                    .clickable { onDetailClick() }
                    .size(24.dp),
        )
    }
}

@Composable
fun TermsDetailDialog(
    title: String,
    termsType: String,
    onDismiss: () -> Unit,
    callGuardRepository: CallGuardRepositoryInterface,
) {
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }

    LaunchedEffect(termsType) {
        try {
            isLoading = true
            error = null

            // 실제 API 호출
            val result = callGuardRepository.getTerms(termsType = termsType, lang = "kr")
            result.fold(
                onSuccess = { termsContent ->
                    content = termsContent
                },
                onFailure = { exception ->
                    Log.e("TermsDetailDialog", "약관 조회 실패: ${exception.message}", exception)
                    error = "약관을 불러오는 데 실패했습니다: ${exception.message}"
                    // 임시로 하드코딩된 내용 사용 (API 실패 시 fallback)
                    when (termsType) {
                        "terms_of_use" -> content = getTermsOfServiceContent()
                        "privacy_policy" -> content = getPrivacyPolicyContent()
                        "marketing_consent" -> content = getMarketingAgreementContent()
                        else -> content = "약관 내용을 찾을 수 없습니다."
                    }
                },
            )
            isLoading = false
        } catch (e: Exception) {
            Log.e("TermsDetailDialog", "약관 조회 중 예외 발생", e)
            error = "약관을 불러오는 데 실패했습니다."
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
        },
        text = {
            Column {
                Text(
                    text = "필수항목 및 선택항목 약관에 동의해 주세요.",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (isLoading) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (error != null) {
                    Text(
                        text = error!!,
                        color = Color.Red,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(400.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = content,
                            fontSize = 14.sp,
                            color = Color.Black,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF41BCD8),
                    ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = "동의하고 가입하기",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(16.dp),
    )
}

/**
 * 서비스 이용약관 내용
 */
private fun getTermsOfServiceContent(): String {
    return """
        제1조 (목적)
        이 약관은 [회사명] (이하 "회사")가 운영하는 "서비스명" 이용약관을 규정함에 목적이 있습니다.

        제2조 (용어의 정의)
        "서비스"란 회사가 제공하는 콜스팸, 모바일 애플리케이션을 통한 제품 및 서비스를 의미합니다.

        제3조 (약관의 공시 및 변경)
        본 약관은 서비스 화면에 게시하거나 기타의 방법으로 회원에게 공지함으로써 효력을 발생합니다.

        제4조 (회원가입)
        서비스를 이용하고자 하는 자는 회사가 정한 가입 양식에 따라 회원정보를 기입한 후 이 약관에 동의한다는 의사표시를 함으로써 회원가입을 신청합니다.

        제5조 (회원정보의 변경)
        회원은 개인정보관리화면을 통하여 언제든지 본인의 개인정보를 열람하고 수정할 수 있습니다.

        제6조 (서비스의 제공)
        회사는 다음과 같은 서비스를 제공합니다:
        1. 스팸전화 차단 서비스
        2. 전화번호 조회 서비스
        3. 기타 회사가 정하는 서비스

        제7조 (서비스의 중단)
        회사는 시스템 정기점검, 증설 및 교체를 위해 당분간 서비스 제공을 중단할 수 있습니다.

        제8조 (회원의 의무)
        회원은 다음 행위를 하여서는 안 됩니다:
        1. 신청 또는 변경시 허위내용의 등록
        2. 타인의 정보도용
        3. 회사가 게시한 정보의 변경
        4. 기타 불법적이거나 부당한 행위

        제9조 (개인정보보호)
        회사는 관련법령이 정하는 바에 따라 회원의 개인정보를 보호하기 위해 노력합니다.

        제10조 (계약해지 및 이용제한)
        회원이 이용계약을 해지하고자 하는 때에는 회원 본인이 온라인을 통해 해지신청을 하여야 합니다.

        제11조 (손해배상)
        회사는 무료로 제공되는 서비스와 관련하여 회원에게 어떠한 손해가 발생하더라도 동 손해가 회사의 고의 또는 중과실에 의한 경우를 제외하고는 이에 대하여 책임을 부담하지 아니합니다.

        제12조 (면책조항)
        회사는 천재지변 또는 이에 준하는 불가항력으로 인하여 서비스를 제공할 수 없는 경우에는 서비스 제공에 관한 책임이 면제됩니다.

        제13조 (재판권 및 준거법)
        서비스 이용으로 발생한 분쟁에 대해 소송이 제기되는 경우 회사의 본사 소재지를 관할하는 법원을 전속관할법원으로 합니다.

        부칙
        본 약관은 2024년 1월 1일부터 적용됩니다.
        """.trimIndent()
}

/**
 * 개인정보 처리방침 내용
 */
private fun getPrivacyPolicyContent(): String {
    return """
        제1조 (개인정보의 처리 목적)
        회사는 다음의 목적을 위하여 개인정보를 처리합니다:
        1. 서비스 제공에 관한 계약 이행 및 서비스 제공에 따른 요금정산
        2. 회원 관리
        3. 마케팅 및 광고에의 활용

        제2조 (개인정보의 처리 및 보유 기간)
        회사는 법령에 따른 개인정보 보유·이용기간 또는 정보주체로부터 개인정보를 수집 시에 동의받은 개인정보 보유·이용기간 내에서 개인정보를 처리·보유합니다.

        제3조 (처리하는 개인정보의 항목)
        회사는 다음의 개인정보 항목을 처리하고 있습니다:
        1. 필수항목: 이메일, 휴대전화번호, 접속 로그, 쿠키, 접속 IP 정보
        2. 선택항목: 프로필 정보

        제4조 (개인정보의 제3자 제공)
        회사는 원칙적으로 정보주체의 개인정보를 수집·이용 목적으로 명시한 범위 내에서 처리하며, 정보주체의 사전 동의 없이는 본래의 목적 범위를 초과하여 처리하거나 제3자에게 제공하지 않습니다.

        제5조 (개인정보처리의 위탁)
        회사는 원활한 개인정보 업무처리를 위하여 다음과 같이 개인정보 처리업무를 위탁하고 있습니다:
        1. 클라우드 서비스 제공업체
        2. 고객지원 서비스 제공업체

        제6조 (정보주체의 권리·의무 및 행사방법)
        정보주체는 회사에 대해 언제든지 다음 각 호의 개인정보 보호 관련 권리를 행사할 수 있습니다:
        1. 개인정보 처리정지 요구
        2. 개인정보 열람요구
        3. 개인정보 정정·삭제요구
        4. 개인정보 처리정지 요구

        제7조 (개인정보의 파기)
        회사는 개인정보 보유기간의 경과, 처리목적 달성 등 개인정보가 불필요하게 되었을 때에는 지체없이 해당 개인정보를 파기합니다.

        제8조 (개인정보 보호책임자)
        회사는 개인정보 처리에 관한 업무를 총괄해서 책임지고, 개인정보 처리와 관련한 정보주체의 불만처리 및 피해구제 등을 위하여 아래와 같이 개인정보 보호책임자를 지정하고 있습니다.

        개인정보 보호책임자
        - 이름: [담당자명]
        - 연락처: [이메일], [전화번호]

        제9조 (개인정보의 안전성 확보조치)
        회사는 개인정보의 안전성 확보를 위해 다음과 같은 조치를 취하고 있습니다:
        1. 관리적 조치: 내부관리계획 수립·시행, 정기적 직원 교육 등
        2. 기술적 조치: 개인정보처리시스템 등의 접근권한 관리, 접근통제시스템 설치, 고유식별정보 등의 암호화, 보안프로그램 설치
        3. 물리적 조치: 전산실, 자료보관실 등의 접근통제

        제10조 (개인정보 처리방침 변경)
        이 개인정보처리방침은 시행일로부터 적용되며, 법령 및 방침에 따른 변경내용의 추가, 삭제 및 정정이 있는 경우에는 변경사항의 시행 7일 전부터 공지사항을 통하여 고지할 것입니다.
        """.trimIndent()
}

/**
 * 마케팅 정보 수신 동의 내용
 */
private fun getMarketingAgreementContent(): String {
    return """
        제1조 (목적)
        본 약관은 마케팅 정보 수신에 대한 동의사항을 규정합니다.

        제2조 (수집하는 개인정보)
        마케팅 정보 제공을 위해 다음 정보를 수집합니다:
        1. 이메일 주소
        2. 휴대전화번호
        3. 서비스 이용 기록

        제3조 (개인정보의 이용 목적)
        수집된 개인정보는 다음의 목적으로 이용됩니다:
        1. 신제품 및 서비스 정보 안내
        2. 이벤트 및 프로모션 안내
        3. 설문조사 및 맞춤형 서비스 제공

        제4조 (개인정보의 보유 및 이용기간)
        마케팅 정보 수신 동의 철회 시까지 보유하며, 동의 철회 시 즉시 파기합니다.

        제5조 (동의 거부권 및 불이익)
        귀하는 마케팅 정보 수신에 대한 동의를 거부할 권리가 있으며, 동의 거부 시에도 서비스 이용에는 제한이 없습니다.

        제6조 (수신 거부 및 동의철회)
        마케팅 정보 수신을 원하지 않으실 경우:
        1. 이메일 하단의 '수신거부' 링크 클릭
        2. 앱 내 설정에서 알림 설정 변경
        3. 고객센터를 통한 수신거부 요청

        제7조 (제3자 제공)
        수집된 개인정보는 마케팅 목적으로 제3자에게 제공되지 않습니다.

        제8조 (개인정보 보호)
        마케팅 목적으로 수집된 개인정보는 관련 법령에 따라 안전하게 관리됩니다.

        ※ 본 동의는 선택사항이며, 동의하지 않으셔도 서비스 이용에는 지장이 없습니다.
        """.trimIndent()
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun TermsAgreementScreenPreview() {
    // Preview용 mock repository
    val mockRepository =
        object : CallGuardRepositoryInterface {
            override suspend fun downloadSTTModel() = Result.success(STTModelData("", ""))

            override suspend fun snsLogin(googleToken: String) = Result.success(LoginData("", ""))

            override suspend fun updateMarketingAgreement(isAgreeMarketing: String) = Result.success(Unit)

            override suspend fun updatePushToken(fcmToken: String) = Result.success(Unit)

            override suspend fun getCDNUrl() = Result.success(CDNUrlData("", ""))

            override suspend fun uploadAudioToCDN(
                uploadUrl: String,
                audioFile: java.io.File,
            ) = Result.success(Unit)

            override suspend fun sendVoiceText(
                uuid: String,
                callText: String,
            ) = Result.success(Unit)

            override suspend fun saveAuthToken(token: String) {}

            override suspend fun getAuthToken(): String? = null

            override suspend fun clearAuthToken() {}

            override suspend fun isLoggedIn(): Boolean = false

            override suspend fun downloadFile(
                url: String,
                outputFile: java.io.File,
                onProgress: kotlinx.coroutines.flow.MutableStateFlow<Double>?,
                expectedMD5: String?,
            ) = Result.success(java.io.File(""))

            override fun isFileExists(file: java.io.File): Boolean = false

            override suspend fun getTerms(
                termsType: String,
                lang: String,
                version: Int?,
            ) = Result.success("Preview 약관 내용")
        }

    MaterialTheme {
        TermsAgreementScreen(
            callGuardRepository = mockRepository,
            onAgreementComplete = { _, _, _ -> },
            onBackPressed = {},
        )
    }
}
