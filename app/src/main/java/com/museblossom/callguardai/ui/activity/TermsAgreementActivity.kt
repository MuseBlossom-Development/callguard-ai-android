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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.museblossom.callguardai.data.model.LoginData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.museblossom.callguardai.domain.repository.CallGuardRepositoryInterface

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
                        finish()
                    }
                )
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
                        if (agreedToMarketing) {
                            Log.d(TAG, "마케팅 동의 업데이트 중...")
                            callGuardRepository.updateMarketingAgreement(true)
                                .onFailure { e ->
                                    Log.e(TAG, "마케팅 동의 업데이트 실패", e)
                                    // 마케팅 동의 실패는 무시하고 진행
                                }
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
                                proceedToMain()
                            }
                            .addOnFailureListener { firebaseException ->
                                Log.e(TAG, "Firebase Auth 로그인 실패", firebaseException)
                                // Firebase 로그인 실패해도 서버 로그인은 성공했으므로 진행
                                Toast.makeText(
                                    this@TermsAgreementActivity,
                                    "로그인이 완료되었습니다!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                proceedToMain()
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
                Toast.makeText(this@TermsAgreementActivity, "서버 통신 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
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

@Composable
fun TermsAgreementScreen(
    onAgreementComplete: (Map<String, Boolean>) -> Unit,
    onBackPressed: () -> Unit
) {
    var allChecked by remember { mutableStateOf(false) }
    var termsChecked by remember { mutableStateOf(false) }
    var privacyChecked by remember { mutableStateOf(false) }
    var marketingChecked by remember { mutableStateOf(false) }
    
    LaunchedEffect(termsChecked, privacyChecked, marketingChecked) {
        allChecked = termsChecked && privacyChecked && marketingChecked
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = "서비스 이용약관",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "CallGuard AI 서비스를 이용하시려면\n아래 약관에 동의해 주세요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            // 전체 동의
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
                    containerColor = if (allChecked) MaterialTheme.colorScheme.primaryContainer 
                                   else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (allChecked) Icons.Filled.CheckCircle 
                                     else Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = if (allChecked) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "모두 동의합니다",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 개별 약관들
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // 서비스 이용약관 (필수)
                TermsItem(
                    title = "[필수] 서비스 이용약관",
                    checked = termsChecked,
                    onCheckedChange = { termsChecked = it },
                    onDetailClick = { /* TODO: 약관 상세 보기 */ }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 개인정보 처리방침 (필수)
                TermsItem(
                    title = "[필수] 개인정보 처리방침",
                    checked = privacyChecked,
                    onCheckedChange = { privacyChecked = it },
                    onDetailClick = { /* TODO: 개인정보 처리방침 상세 보기 */ }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 마케팅 정보 수신 (선택)
                TermsItem(
                    title = "[선택] 마케팅 정보 수신 동의",
                    checked = marketingChecked,
                    onCheckedChange = { marketingChecked = it },
                    onDetailClick = { /* TODO: 마케팅 약관 상세 보기 */ },
                    isOptional = true
                )
            }
            
            // 동의하고 시작하기 버튼
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
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "동의하고 시작하기",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
fun TermsItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDetailClick: () -> Unit,
    isOptional: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        
        Text(
            text = "보기",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onDetailClick() }
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
