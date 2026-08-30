package com.owen282000.lifedashboard.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.ConnectionCapabilities
import com.owen282000.lifedashboard.EnrollmentClient
import com.owen282000.lifedashboard.EnrollmentClientInfo
import com.owen282000.lifedashboard.EnrollmentInputParser
import com.owen282000.lifedashboard.EnrollmentRequest
import com.owen282000.lifedashboard.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectionScreen() {
    val context = LocalContext.current
    val preferences = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()
    var enrollmentString by remember { mutableStateOf("") }
    var enrolling by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(preferences.getConnectionProfile()) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Link, contentDescription = null)
                    Text("Connect once", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Paste the one-time enrollment string from your server. It configures Health and Screen Time together."
                )
            }
        }

        OutlinedTextField(
            value = enrollmentString,
            onValueChange = { enrollmentString = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enrollment string") },
            placeholder = { Text("lifedashboard://enroll?endpoint=…#code=…") },
            singleLine = true,
            enabled = !enrolling,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text("The code is single-use. Long-lived credentials are returned over HTTPS and stored encrypted.")
            }
        )

        Button(
            onClick = {
                scope.launch {
                    enrolling = true
                    val result = runCatching {
                        val input = EnrollmentInputParser.parse(enrollmentString)
                        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        val request = EnrollmentRequest(
                            code = input.code,
                            client = EnrollmentClientInfo(
                                appId = "life-dashboard-android",
                                appVersion = packageInfo.versionName ?: "unknown",
                                installationId = preferences.getInstallationId()
                            ),
                            requestedCapabilities = listOf(
                                ConnectionCapabilities.HEALTH,
                                ConnectionCapabilities.SCREEN_TIME
                            )
                        )
                        withContext(Dispatchers.IO) { EnrollmentClient().exchange(input, request) }
                    }
                    result.onSuccess {
                        preferences.applyEnrollmentProfile(it)
                        profile = preferences.getConnectionProfile()
                        enrollmentString = ""
                        Toast.makeText(context, "Connected to ${it.name}", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Enrollment failed", Toast.LENGTH_LONG).show()
                    }
                    enrolling = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enrollmentString.isNotBlank() && !enrolling
        ) {
            if (enrolling) {
                CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Connect")
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Current profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(profile.name, fontWeight = FontWeight.SemiBold)
                Text("Default endpoints: ${profile.defaultDelivery.webhookUrls.size}")
                Text(
                    "Health: " + if (preferences.isHealthConnectionOverrideEnabled()) "separate override" else "uses default"
                )
                Text(
                    "Screen Time: " + if (preferences.isScreenTimeConnectionOverrideEnabled()) "separate override" else "uses default"
                )
                Text(
                    "Editing webhook settings inside a category creates a separate override. Disable it there to return to this profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
