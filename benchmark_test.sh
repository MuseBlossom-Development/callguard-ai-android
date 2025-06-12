#!/bin/bash

# Whisper.cpp 벤치마크 테스트 스크립트 (원본 방식)
echo "🔥 Whisper.cpp 벤치마크 테스트"
echo "=================================="

DEVICE_ID="R3CRB0S6KJX"
THREADS=2

echo "📱 디바이스: $DEVICE_ID"
echo "🧵 스레드 수: $THREADS"
echo ""

echo "⏳ 벤치마크를 실행합니다. 몇 분이 소요됩니다..."
echo ""

# 앱에서 벤치마크를 실행하도록 유도하는 로그 모니터링
echo "📋 로그 모니터링 시작..."
echo "💡 이제 앱에서 벤치마크를 실행하세요!"
echo ""

# 벤치마크 관련 로그만 필터링
adb -s $DEVICE_ID logcat -c  # 로그 초기화

echo "🔍 벤치마크 결과를 기다리는 중..."
echo "   (앱에서 benchMemory 또는 benchGgmlMulMat 함수를 호출하세요)"
echo ""

# 벤치마크 결과가 나올 때까지 로그 모니터링
adb -s $DEVICE_ID logcat | grep -E "(벤치마크|benchmark|memcpy|ggml_mul_mat|GB/s|ms)" | while read line; do
    echo "📊 $line"
done