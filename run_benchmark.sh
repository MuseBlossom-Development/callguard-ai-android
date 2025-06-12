#!/bin/bash

# Whisper 벤치마크 실행 스크립트
echo "🚀 Whisper 벤치마크 실행"
echo "========================"

# 연결된 디바이스 자동 감지
DEVICE_ID=$(adb devices | grep -v "List of devices attached" | grep "device" | head -1 | cut -f1)
PACKAGE="com.museblossom.callguardai"

if [ -z "$DEVICE_ID" ]; then
    echo "❌ 연결된 Android 디바이스가 없습니다"
    echo "   USB 디버깅이 활성화된 디바이스를 연결하세요"
    exit 1
fi

echo "📱 디바이스: $DEVICE_ID"
echo "📦 패키지: $PACKAGE"
echo ""

# 1. 앱이 설치되어 있는지 확인
echo "🔍 앱 설치 확인..."
if adb -s $DEVICE_ID shell pm list packages | grep -q $PACKAGE; then
    echo "✅ 앱이 설치되어 있습니다"
else
    echo "❌ 앱이 설치되어 있지 않습니다"
    echo "   먼저 앱을 빌드하고 설치하세요: ./gradlew installDebug"
    exit 1
fi

echo ""
echo "🎯 벤치마크 액티비티 실행..."

# 2. 벤치마크 액티비티 실행
adb -s $DEVICE_ID shell am start -n "$PACKAGE/.ui.benchmark.BenchmarkActivity"

echo ""
echo "📋 로그 모니터링 시작..."
echo "   (Ctrl+C로 중지)"
echo ""

# 3. 벤치마크 로그 모니터링
adb -s $DEVICE_ID logcat -c  # 로그 초기화
adb -s $DEVICE_ID logcat | grep -E "(BenchmarkActivity|벤치마크|benchmark|memcpy|ggml_mul_mat|GB/s|ms|Whisper)" | while read line; do
    echo "📊 $line"
done
