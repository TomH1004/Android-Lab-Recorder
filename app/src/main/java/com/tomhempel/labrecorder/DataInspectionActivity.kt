package com.tomhempel.labrecorder

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomhempel.labrecorder.ui.theme.LabRecorderTheme
import java.io.File

class DataInspectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LabRecorderTheme {
                DataInspectionScreen(
                    onNavigateBack = { finish() },
                    onRecordingSelected = { recordingPath, isSingleParticipant ->
                        val intent = Intent(this, TimeNormalizedPlotActivity::class.java).apply {
                            putExtra("RECORDING_PATH", recordingPath)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

enum class RecordingType {
    SINGLE, GROUP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataInspectionScreen(
    onNavigateBack: () -> Unit,
    onRecordingSelected: (String, Boolean) -> Unit
) {
    val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "LabRecorder")
    var recordings by remember { mutableStateOf(listRecordings(baseDir)) }
    var selectedTab by remember { mutableStateOf(RecordingType.SINGLE) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Recorded Data") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                TabRow(
                    selectedTabIndex = if (selectedTab == RecordingType.SINGLE) 0 else 1
                ) {
                    Tab(
                        selected = selectedTab == RecordingType.SINGLE,
                        onClick = { selectedTab = RecordingType.SINGLE },
                        text = { Text("Single Recordings") }
                    )
                    Tab(
                        selected = selectedTab == RecordingType.GROUP,
                        onClick = { selectedTab = RecordingType.GROUP },
                        text = { Text("Group Recordings") }
                    )
                }
            }
        }
    ) { padding ->
        val filteredRecordings = recordings.filter { 
            when (selectedTab) {
                RecordingType.SINGLE -> it.isSingleParticipant
                RecordingType.GROUP -> !it.isSingleParticipant
            }
        }

        if (filteredRecordings.isEmpty()) {
            // Show empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when (selectedTab) {
                        RecordingType.SINGLE -> "No single participant recordings found"
                        RecordingType.GROUP -> "No group recordings found"
                    },
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredRecordings) { recording ->
                    RecordingItem(
                        recording = recording,
                        onClick = {
                            val recordingPath = if (recording.isSingleParticipant) {
                                File(baseDir, "SingleRecordings/${recording.name}").absolutePath
                            } else {
                                File(baseDir, recording.name).absolutePath
                            }
                            onRecordingSelected(recordingPath, recording.isSingleParticipant)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingItem(
    recording: RecordingInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = recording.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (recording.isSingleParticipant) 
                    "Participant ID: ${recording.name}" 
                else 
                    "Group ID: ${recording.name}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Files: ${recording.fileCount}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class RecordingInfo(
    val name: String,
    val isSingleParticipant: Boolean,
    val fileCount: Int
)

private fun listRecordings(baseDir: File): List<RecordingInfo> {
    if (!baseDir.exists()) return emptyList()

    val recordings = mutableListOf<RecordingInfo>()

    // Check SingleRecordings directory
    val singleRecordingsDir = File(baseDir, "SingleRecordings")
    if (singleRecordingsDir.exists()) {
        singleRecordingsDir.listFiles()?.forEach { participantDir ->
            if (participantDir.isDirectory) {
                recordings.add(
                    RecordingInfo(
                        name = participantDir.name,
                        isSingleParticipant = true,
                        fileCount = participantDir.listFiles()?.size ?: 0
                    )
                )
            }
        }
    }

    // Check group recordings (directories directly in baseDir except SingleRecordings)
    baseDir.listFiles()?.forEach { dir ->
        if (dir.isDirectory && dir.name != "SingleRecordings") {
            recordings.add(
                RecordingInfo(
                    name = dir.name,
                    isSingleParticipant = false,
                    fileCount = dir.listFiles()?.size ?: 0
                )
            )
        }
    }

    return recordings.sortedBy { it.name }
} 