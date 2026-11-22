package com.writeright.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.writeright.ai.ai.AiSettingsStore

@Composable
fun AiSettingsDialog(
    savedApiKey: String,
    savedModel: String,
    status: String,
    onSave: (String, String) -> Unit,
    onTest: (String, String, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(savedApiKey) }
    var model by remember { mutableStateOf(savedModel.ifBlank { AiSettingsStore.DEFAULT_MODEL }) }
    var testResult by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعدادات الذكاء الاصطناعي") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("اربط OpenRouter عشان التطبيق يراجع صورة خطك والنص المستخرج.")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testResult = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenRouter API Key") },
                    placeholder = { Text("sk-or-v1-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; testResult = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Vision model") },
                    supportingText = { Text("الافتراضي: ${AiSettingsStore.DEFAULT_MODEL}") },
                    singleLine = true,
                )
                Text(status)
                if (testResult.isNotBlank()) Text(testResult)
                Text(
                    "المفتاح بيتخزن مشفرًا على الجهاز. استخدم موديل يدعم إدخال الصور، وتأكد من وجود رصيد أو حد مجاني في OpenRouter.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(apiKey, model)
                    onDismiss()
                },
                enabled = apiKey.isNotBlank() && model.isNotBlank(),
            ) { Text("حفظ") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = {
                        if (apiKey.isBlank() || model.isBlank()) testResult = "أدخل المفتاح واسم الموديل أولًا"
                        else onTest(apiKey, model) { testResult = it }
                    },
                ) { Text("اختبار الاتصال") }
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            }
        },
    )
}
