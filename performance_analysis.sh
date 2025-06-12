#!/bin/bash

# Whisper.cpp 성능 분석 스크립트
echo "=================================="
echo "🚀 Whisper.cpp 성능 분석 시작"
echo "=================================="

# 디바이스 선택
DEVICE_ID="R3CRB0S6KJX"
echo "🔌 사용 디바이스: $DEVICE_ID"

# 디바이스 정보 확인
echo ""
echo "📱 디바이스 정보:"
adb -s $DEVICE_ID shell "getprop ro.product.model"
adb -s $DEVICE_ID shell "getprop ro.product.cpu.abi"
adb -s $DEVICE_ID shell "cat /proc/cpuinfo | grep processor | wc -l" | tr -d '\r' | xargs echo "CPU 코어 수:"

# 메모리 정보
echo ""
echo "💾 메모리 정보:"
adb -s $DEVICE_ID shell "cat /proc/meminfo | grep MemTotal"
adb -s $DEVICE_ID shell "cat /proc/meminfo | grep MemAvailable"

# CPU 주파수 정보
echo ""
echo "⚡ CPU 주파수:"
echo -n "Little 코어: "
adb -s $DEVICE_ID shell "cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null || echo 'N/A'"
echo -n "Big 코어: "
adb -s $DEVICE_ID shell "cat /sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq 2>/dev/null || echo 'N/A'"

echo ""
echo "=================================="
echo "📊 성능 비교 기준"
echo "=================================="

echo "🎯 벤치마크 결과 분석:"
echo "- 당신의 디바이스: 17.04 GB/s (2스레드 최적)"
echo "- 참고 비교:"
echo "  • MacBook M1: ~25-30 GB/s"
echo "  • Snapdragon 855: ~12-15 GB/s"
echo "  • Snapdragon 888: ~18-22 GB/s"
echo "  • Snapdragon 8 Gen 1: ~20-25 GB/s"

echo ""
echo "⏱️ 예상 Whisper 성능 (최적화 후):"
echo "- 10초 오디오 → 6-7초 전사 (0.6-0.7x 실시간)"
echo "- 15초 오디오 → 9-11초 전사"
echo "- 이전 12초 → 예상 6-8초로 개선 ✨"

echo ""
echo "🔧 적용된 최적화:"
echo "✅ 스레드 수: 4개 → 2개 (메모리 대역폭 최적화)"
echo "✅ single_segment: false → true (오버헤드 감소)"
echo "✅ no_context: true → false (컨텍스트 활용)"
echo "✅ beam_size: 기본값 → 1 (그리디 디코딩)"
echo "✅ 추가 매개변수 최적화"

echo ""
echo "📋 실시간 모니터링:"
echo "adb -s $DEVICE_ID logcat | grep -E '(Whisper|JNI|통화녹음서비스)'"
echo ""
echo "🚀 이제 앱을 테스트해보세요!"
