package com.xiaozhi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * 家长算式验证门禁（自 StudyScreen 内联实现抽取）：
 * 家长中心入口与"家长解锁答案"共用，验证通过后回调 onVerified。
 */
@Composable
fun ParentGateDialog(
    description: String,
    onVerified: () -> Unit,
    onDismiss: () -> Unit
) {
    val gate = remember {
        // 两个小算式，避开常见"验证码"答案，孩子不容易蒙对
        (System.currentTimeMillis() % 37L).toInt() + 11 to
            ((System.currentTimeMillis() / 7L % 29L).toInt() + 7)
    }
    var answer by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("家长确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description)
                OutlinedTextField(
                    value = answer,
                    onValueChange = { value -> answer = value.filter(Char::isDigit).take(3) },
                    label = { Text("家长验证：${gate.first} + ${gate.second} = ?") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onVerified()
                    onDismiss()
                },
                enabled = answer.toIntOrNull() == gate.first + gate.second
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
