package com.tomhempel.labrecorder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomhempel.labrecorder.ui.theme.LabRecorderTheme

class ParticipantSelectionActivity : ComponentActivity() {
    private var showExitDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LabRecorderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showExitDialog) {
                        AlertDialog(
                            onDismissRequest = { showExitDialog = false },
                            title = { Text("Exit App") },
                            text = { Text("Do you want to exit the app?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showExitDialog = false
                                    finish()
                                }) {
                                    Text("Yes")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showExitDialog = false }) {
                                    Text("No")
                                }
                            }
                        )
                    }

                    ParticipantSelectionScreen(
                        onSelectionMade = { numberOfParticipants ->
                            val intent = Intent(this, MainActivity::class.java).apply {
                                putExtra("NUMBER_OF_PARTICIPANTS", numberOfParticipants)
                            }
                            startActivity(intent)
                            finish() // Close this activity
                        },
                        onInspectData = {
                            val intent = Intent(this, DataInspectionActivity::class.java)
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onBackPressed() {
        showExitDialog = true
    }
}

@Composable
fun ParticipantSelectionScreen(
    onSelectionMade: (Int) -> Unit,
    onInspectData: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo
            Image(
                painter = painterResource(id = R.mipmap.logo),
                contentDescription = "App Logo",
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = "Lab Recorder",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Description
            Text(
                text = "Select the number of participants for this recording session",
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Selection Buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onSelectionMade(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 32.dp)
                ) {
                    Text("Single Participant", fontSize = 18.sp)
                }

                Button(
                    onClick = { onSelectionMade(2) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 32.dp)
                ) {
                    Text("Two Participants", fontSize = 18.sp)
                }
            }
        }

        // Divider and Data Inspection Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 30.dp) // Added extra padding at the bottom
        ) {
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                thickness = 2.dp
            )

            OutlinedButton(
                onClick = onInspectData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 32.dp)
            ) {
                Text("Inspect Recorded Data", fontSize = 16.sp)
            }
        }
    }
} 