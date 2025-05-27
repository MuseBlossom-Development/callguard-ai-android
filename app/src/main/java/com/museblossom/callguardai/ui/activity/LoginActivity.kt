package com.museblossom.callguardai.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View.GONE
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.ActivityLoginBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Firebase Auth 초기화
        auth = FirebaseAuth.getInstance()
        credentialManager = CredentialManager.create(this)

        setupUI()
    }

    private fun setupUI() {
        binding.btnSkip.visibility = GONE
        binding.btnGoogleLogin.setOnClickListener {
            signInWithGoogle()
        }

        binding.btnSkip.setOnClickListener {
            // 건너뛰기 기능 (필요시)
//            proceedToMain()
        }
    }

    private fun signInWithGoogle() {
        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(true)
            .build()

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@LoginActivity,
                )
                handleSignIn(result)
            } catch (e: GetCredentialException) {
                Log.e("구글로그인", "자격증명 요청 오류", e)
                Toast.makeText(this@LoginActivity, "로그인에 실패했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSignIn(result: GetCredentialResponse) {
        val credential = result.credential
        Log.d("구글로그인", "자격증명 타입: ${credential::class.java.simpleName}")
        Log.d("구글로그인", "자격증명 데이터: ${credential.data}")

        when (credential) {
            is GoogleIdTokenCredential -> {
                try {
                    val googleIdToken = credential.idToken
                    Log.d("구글로그인", "구글 ID 토큰을 받았습니다")

                    // 자체 서버로 토큰 전송하여 검증
                    sendTokenToServer(googleIdToken)

                } catch (e: GoogleIdTokenParsingException) {
                    Log.e("구글로그인", "구글 ID 토큰 파싱 오류", e)
                    Toast.makeText(this, "Google ID 토큰 파싱에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                Log.e("구글로그인", "예상치 못한 자격증명 타입: ${credential::class.java.name}")
                Log.e("구글로그인", "사용 가능한 데이터 키들: ${credential.data?.keySet()}")

                // 혹시 다른 방식으로 Google ID Token을 추출할 수 있는지 시도
                try {
                    val bundle = credential.data
                    val idToken =
                        bundle?.getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN")
                    if (idToken != null) {
                        Log.d("구글로그인", "번들에서 토큰 추출 성공")
                        sendTokenToServer(idToken)
                    } else {
                        Toast.makeText(this, "예상치 못한 자격 증명 유형입니다.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("구글로그인", "번들에서 토큰 추출 실패", e)
                    Toast.makeText(this, "예상치 못한 자격 증명 유형입니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendTokenToServer(googleIdToken: String) {
        // TODO: 자체 서버 API 호출 구현
        // 예시:
        // 1. 구글 토큰을 서버로 전송
        // 2. 서버에서 토큰 검증 후 자체 인증 토큰 반환
        // 3. 성공 시 proceedToMain() 호출

        Log.d("서버통신", "구글 토큰을 서버로 전송: ${googleIdToken.substring(0, 20)}...")

        // 임시로 성공 처리 (실제 서버 API 구현 필요)
        Toast.makeText(this, "서버 인증 성공!", Toast.LENGTH_SHORT).show()
        proceedToMain()
    }

    private fun proceedToMain() {
        val intent = Intent(this, SplashActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }
}
