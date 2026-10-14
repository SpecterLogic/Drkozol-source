package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
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

    private fun showMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
