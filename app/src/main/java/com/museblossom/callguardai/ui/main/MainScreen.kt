package com.whispercppdemo.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import com.museblossom.callguardai.R
import com.museblossom.callguardai.ui.main.MainScreenViewModel


@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    MainScreen(
        canTranscribe    = viewModel.canTranscribe,
        isRecording      = viewModel.isRecording,
        messageLog       = viewModel.dataLog,
        onBenchmarkTapped        = viewModel::benchmark,
        onTranscribeSampleTapped = viewModel::transcribeSample,
        onRecordTapped           = viewModel::toggleRecord,
        sampleFiles      = viewModel.sampleFiles,
        onSampleSelected = { file -> viewModel.transcribeSampleFile(file) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    canTranscribe: Boolean,
    isRecording: Boolean,
    messageLog: String,
    onBenchmarkTapped: () -> Unit,
    onTranscribeSampleTapped: () -> Unit,
    onRecordTapped: () -> Unit,
    sampleFiles: List<File>,
    onSampleSelected: (File) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())          // 전체 스크롤 적용
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BenchmarkButton(enabled = canTranscribe, onClick = onBenchmarkTapped)
                TranscribeSampleButton(enabled = canTranscribe, onClick = onTranscribeSampleTapped)
            }

            Spacer(modifier = Modifier.height(8.dp))

            RecordButton(
                enabled     = canTranscribe,
                isRecording = isRecording,
                onClick     = onRecordTapped
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Sample List", style = MaterialTheme.typography.titleMedium)
            SampleList(
                sampleFiles  = sampleFiles,
                onItemClick  = onSampleSelected,
                modifier     = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 0.dp, max = 300.dp)        // 높이 제한
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Log", style = MaterialTheme.typography.titleMedium)
            MessageLog(
                log      = messageLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 300.dp)     // 높이 제한
            )
        }
    }
}

@Composable
fun SampleList(
    sampleFiles: List<File>,
    onItemClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(sampleFiles) { file ->
            ListItem(
                headlineContent = { Text(text = file.name) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick(file) }
            )
            Divider()
        }
    }
}

@Composable
private fun MessageLog(
    log: String,
    modifier: Modifier = Modifier
) {
    SelectionContainer {
        Text(
            text = log,
            modifier = modifier
                .verticalScroll(rememberScrollState())      // 로그만 별도로 스크롤
        )
    }
}

@Composable
private fun BenchmarkButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text("Benchmark")
    }
}

@Composable
private fun TranscribeSampleButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text("Transcribe sample")
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun RecordButton(
    enabled: Boolean,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val micPermissionState = rememberPermissionState(
        permission = android.Manifest.permission.RECORD_AUDIO,
        onPermissionResult = { granted ->
            if (granted) onClick()
        }
    )

    Button(
        onClick = {
            if (micPermissionState.status.isGranted) {
                onClick()
            } else {
                micPermissionState.launchPermissionRequest()
            }
        },
        enabled = enabled
    ) {
        Text(if (isRecording) "Stop recording" else "Start recording")
    }
}