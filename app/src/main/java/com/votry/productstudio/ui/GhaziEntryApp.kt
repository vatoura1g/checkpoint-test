package com.votry.productstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.votry.productstudio.cloud.CloudflareConfigStore

@Composable
fun GhaziEntryApp() {
    val context = LocalContext.current
    val store = remember { CloudflareConfigStore(context) }
    var enterApp by remember { mutableStateOf(store.isConfigured()) }

    if (enterApp) {
        ProductStudioApp()
        return
    }

    var accountId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color.White).padding(26.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("GHAZI", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.Black)
                Text("AI Ultra — configuration propriétaire", fontSize = 17.sp, color = Color(0xFF555555))
                Spacer(Modifier.height(28.dp))
                Text(
                    "À faire une seule fois. Les identifiants restent chiffrés sur ce téléphone.",
                    fontSize = 16.sp,
                    color = Color(0xFF444444),
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it },
                    label = { Text("Cloudflare Account ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Workers AI API Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color(0xFF9D1C1C), fontSize = 15.sp)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        runCatching { store.save(accountId, token) }
                            .onSuccess { enterApp = true }
                            .onFailure { error = it.message ?: "Configuration invalide" }
                    },
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                ) {
                    Text("ACTIVER AI ULTRA", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { enterApp = true },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEDEDED), contentColor = Color.Black),
                ) {
                    Text("CONTINUER EN MODE LOCAL", fontSize = 16.sp)
                }
                Spacer(Modifier.height(18.dp))
                Text("Ghazi Fourati — Special Edition", fontSize = 13.sp, color = Color(0xFF777777))
            }
        }
    }
}
