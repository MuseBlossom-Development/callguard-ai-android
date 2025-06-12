override fun onDestroy() {
    super.onDestroy()
    Log.d(TAG, "통화녹음 서비스 종료 중")

        // 먼저 오버레이 제거
        removeOverlayView()
        isOverlayCurrentlyVisible = false
        serviceInstance = null

        // Whisper 리소스 해제를 위한 별도 스코프 생성
        val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        cleanupScope.launch {
            try {
                whisperContext?.release()
                Log.d(TAG, "WhisperContext 해제 완료")
            } catch (e: Exception) {
                Log.w(TAG, "WhisperContext 해제 중 오류: ${e.message}")
            } finally {
                whisperContext = null
                cleanupScope.cancel()
            }
        }

        // 기존 서비스 스코프 취소
        serviceScope.cancel()

        Log.d(TAG, "통화녹음 서비스 onDestroy 완료")
    }

    private fun setRecordListener() {
        Log.d(TAG, "RecordListener 설정")
        recorder.setRecordListner(object : EnhancedRecorderListener {
            override fun onWaveConvertComplete(filePath: String?) {
                // 기존 콜백 - 호환성을 위해 유지하지만 새 콜백이 우선
                Log.d(TAG, "기존 콜백 호출됨: $filePath")
            }

            override fun onWaveFileReady(file: File, fileSize: Long, isValid: Boolean) {
                Log.d(TAG, "========================================")
                Log.d(TAG, "WAV 파일 완성 콜백 호출됨")
                Log.d(TAG, "파일: ${file.absolutePath}")
                Log.d(TAG, "크기: $fileSize bytes")
                Log.d(TAG, "유효성: $isValid")
                Log.d(TAG, "실제 파일 존재: ${file.exists()}")
                Log.d(TAG, "실제 파일 크기: ${file.length()}")

                if (!isValid) {
                    Log.e(TAG, "유효하지 않은 파일로 처리 중단")
                    return
                }

                if (!file.exists()) {
                    Log.e(TAG, "파일이 존재하지 않음 - Recorder에서 잘못된 콜백")
                    return
                }

                if (file.length() != fileSize) {
                    Log.w(TAG, "파일 크기 불일치 - 예상: $fileSize, 실제: ${file.length()}")
                }

                serviceScope.launch {
                    try {
                        Log.d(TAG, "decodeWaveFile 시작...")
                        val data = decodeWaveFile(file)
                        Log.d(TAG, "decodeWaveFile 완료 - 데이터 크기: ${data.size}")

                        withContext(Dispatchers.Main) {
                            transcribeWithWhisper(data)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "WAV 파일 디코딩 중 오류: ${e.message}", e)
                    }
                }
                Log.d(TAG, "========================================")
            }
        })
    }

    private fun initializeRecorder() {
        recorder = Recorder(
            context = this,
            callback = { elapsedSeconds ->
                callDuration = elapsedSeconds
                Log.d(TAG, "통화 시간: ${elapsedSeconds}초")
                // 15초마다 세그먼트 파일 처리
                if (elapsedSeconds > 0 && elapsedSeconds % 15 == 0) {
                    Log.d(TAG, "${elapsedSeconds}초 경과, 분석 주기 도달")
                    serviceScope.launch {
                        // 분석을 위해 현재 녹음 중지하고 재시작
                        withContext(Dispatchers.Main) {
                            recorder.stopRecording(false)
                            recorder.startRecording(0, isOnlyWhisper)
                        }
                    }
                }
            },
            detectCallback = { isDeepVoiceDetected: Boolean, probability: Int ->
                serviceScope.launch {
                    handleDeepVoiceAnalysis(probability)
                }
            },
            audioAnalysisRepository = audioAnalysisRepository
        )

        setRecordListener()
    }
