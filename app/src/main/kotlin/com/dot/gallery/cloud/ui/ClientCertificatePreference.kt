package com.dot.gallery.cloud.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dot.gallery.R
import com.dot.gallery.cloud.network.ClientCertificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Shared by setup and settings. Cancellation keeps the existing choice; removal is explicit. */
@Composable
fun ClientCertificatePreference(url: String, certificates: String, onSelected: (String?) -> Unit) {
    val key = ClientCertificates.urlKey(url) ?: return
    val context = LocalContext.current
    val activity = context.findCertificateActivity() ?: return
    val scope = rememberCoroutineScope()
    val alias = ClientCertificates.alias(certificates, url)
    var busy by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cloud_client_certificate), style = MaterialTheme.typography.titleSmall)
        Text(url, style = MaterialTheme.typography.bodySmall)
        Text(alias ?: stringResource(R.string.cloud_client_certificate_none))
        if (alias == null) Text(stringResource(R.string.cloud_client_certificate_hint), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = !busy, onClick = {
                busy = true
                failed = false
                val target = key.toHttpUrl()
                try {
                    KeyChain.choosePrivateKeyAlias(activity, { selected ->
                        scope.launch {
                            try {
                                if (selected != null) {
                                    withContext(Dispatchers.IO) {
                                        checkNotNull(KeyChain.getPrivateKey(context.applicationContext, selected))
                                        val chain = KeyChain.getCertificateChain(context.applicationContext, selected)
                                        check(!chain.isNullOrEmpty())
                                        chain.first().checkValidity()
                                    }
                                    onSelected(selected)
                                }
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    }, null, null, target.host, target.port, alias)
                } catch (_: Exception) {
                    busy = false
                    failed = true
                }
            }) { Text(stringResource(R.string.cloud_client_certificate_select)) }
            if (alias != null) {
                TextButton(enabled = !busy, onClick = { failed = false; onSelected(null) }) {
                    Text(stringResource(R.string.cloud_client_certificate_remove))
                }
            }
        }
        if (failed) Text(stringResource(R.string.cloud_client_certificate_error), color = MaterialTheme.colorScheme.error)
    }
}

private fun Context.findCertificateActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findCertificateActivity()
    else -> null
}
