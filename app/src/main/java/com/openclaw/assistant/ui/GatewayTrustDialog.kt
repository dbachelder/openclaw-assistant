package com.openclaw.assistant.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.node.NodeRuntime

@Composable
fun GatewayTrustDialog(
  prompt: NodeRuntime.GatewayTrustPrompt,
  onAccept: () -> Unit,
  onDecline: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDecline,
    title = { Text(stringResource(R.string.gateway_trust_title)) },
    text = {
      Text(
          stringResource(
              R.string.gateway_trust_message,
              prompt.fingerprintSha256
          )
      )
    },
    confirmButton = {
      TextButton(onClick = onAccept) {
        Text(stringResource(R.string.gateway_trust_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDecline) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}
