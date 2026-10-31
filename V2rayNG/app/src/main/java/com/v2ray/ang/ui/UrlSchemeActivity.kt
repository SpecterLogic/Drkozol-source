package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UrlSchemeActivity : BaseComponentActivity() {
    private val linkViewModel: LinkImportViewModel by viewModels()
    private val routingViewModel: com.v2ray.ang.ui.routing.RoutingSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        parseUri(intent.getStringExtra(Intent.EXTRA_TEXT), null)
                    } else showMain()
                } else if (action == Intent.ACTION_VIEW) {
                    when (data?.host) {
                        "crypt1", "connect", "open", "disconnect", "close", "toggle", "status" -> linkViewModel.onAction(LinkPromptAction.Prepare(data.toString()))
                        "routing", "autorouting" -> lifecycleScope.launch(Dispatchers.IO) {
                            val saved = routingViewModel.importProfileLink(data.toString())
                            withContext(Dispatchers.Main) {
                                toast(if (saved) R.string.toast_success else R.string.toast_failure)
                                showMain()
                            }
                        }
                        "install-config" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameter("url").orEmpty()
                            parseUri(shareUrl, uri?.fragment)
                        }

                        "install-sub" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameter("url").orEmpty()
                            parseUri(shareUrl, uri?.fragment)
                        }

                        else -> {
                            toastError(R.string.toast_failure)
                            showMain()
                        }
                    }
                } else showMain()
            }

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing shared configuration", IllegalStateException(e.javaClass.simpleName))
            showMain()
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by linkViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.completed) { if(state.completed) showMain(state.ticket) }
        if(state.visible) AlertDialog(onDismissRequest={ if(!state.busy) finish() },
            title={ Text(stringResource(R.string.dr_link_confirm)) },
            text={ Text(if(state.command!=null) stringResource(when(state.command) {
                "connect" -> R.string.dr_link_connect; "disconnect" -> R.string.dr_link_disconnect
                "toggle" -> R.string.dr_link_toggle; else -> R.string.dr_link_status
            }) else if(state.error) stringResource(R.string.toast_failure) else (state.name ?: stringResource(R.string.dr_link_subscription)) + "\n••••••••") },
            confirmButton={ TextButton(enabled=!state.busy && !state.error,onClick={ linkViewModel.onAction(LinkPromptAction.Confirm) }) { Text(stringResource(R.string.dr_login)) } },
            dismissButton={ TextButton(enabled=!state.busy,onClick={ finish() }) { Text(stringResource(R.string.dr_cancel)) } })
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            showMain()
            return
        }

        var decodedUrl = uriString.trim()
        val uri = Uri.parse(decodedUrl)
        if (uri != null) {
            if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
                decodedUrl += "#${fragment}"
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val previousSubscriptions = MmkvManager.decodeSubscriptions().map { it.guid }.toSet()
                val (count, countSub) = try {
                    val imported = AngConfigManager.importBatchConfig(decodedUrl, "", false)
                    if (imported.second > 0) {
                        MmkvManager.decodeSubscriptions().filter { it.guid !in previousSubscriptions }
                            .forEach { AngConfigManager.updateConfigViaSub(it) }
                    }
                    imported
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "Shared configuration import failed", IllegalStateException(error.javaClass.simpleName))
                    0 to 0
                }
                withContext(Dispatchers.Main) {
                    if (count + countSub > 0) {
                        toast(R.string.import_subscription_success)
                    } else {
                        toast(R.string.import_subscription_failure)
                    }
                    showMain()
                }
            }
        }
    }

    private fun showMain(ticket: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).apply { ticket?.let { putExtra("dr_confirmed_link",it) } })
        finish()
    }
}
