package com.v2ray.ang.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.SubscriptionLinkCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LinkPromptState(val visible: Boolean=false, val busy: Boolean=false, val name: String?=null,
    val command: String?=null, val completed: Boolean=false, val ticket: String?=null, val error: Boolean=false)
sealed interface LinkPromptAction { data class Prepare(val link: String): LinkPromptAction; data object Confirm: LinkPromptAction }
class LinkImportViewModel(app: Application): AndroidViewModel(app) {
    private val mutableState=MutableStateFlow(LinkPromptState())
    val state=mutableState.asStateFlow()
    private var encoded=""
    fun onAction(action: LinkPromptAction) {
        when(action) {
            is LinkPromptAction.Prepare -> viewModelScope.launch(Dispatchers.Default) {
                encoded=action.link
                mutableState.value=parseLinkPrompt(action.link)
            }
            LinkPromptAction.Confirm -> {
                val current=state.value
                if (!current.visible || current.busy || current.error) return
                mutableState.update { it.copy(busy=true) }
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        if(current.command!=null) {
                            val ticket=com.v2ray.ang.handler.ConfirmedLinkCommands.issue(current.command)
                            mutableState.update { it.copy(completed=true,ticket=ticket,busy=false) }
                        } else {
                            val result=com.v2ray.ang.handler.AngConfigManager.importBatchConfig(encoded,"",false)
                            check(result.first+result.second>0)
                            mutableState.update { it.copy(completed=true,busy=false) }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        com.v2ray.ang.util.LogUtil.e(com.v2ray.ang.AppConfig.TAG,"Confirmed link import failed",IllegalStateException(error.javaClass.simpleName))
                        mutableState.update { it.copy(error=true,busy=false) }
                    }
                }
            }
        }
    }
}

internal fun parseLinkPrompt(link: String): LinkPromptState = runCatching {
    require(link.length <= 20_000)
    val uri=java.net.URI(link)
    val command=if(uri.scheme=="drkozol" && uri.rawQuery==null && uri.rawUserInfo==null && uri.path.orEmpty() in setOf("","/"))
        when(uri.host) { "connect","open" -> "connect"; "disconnect","close" -> "disconnect"; "toggle" -> "toggle"; "status" -> "status"; else -> null }
        else null
    val decoded=if(command==null) SubscriptionLinkCodec.decode(link) else null
    LinkPromptState(visible=true,name=decoded?.name,command=command,error=command==null && decoded==null)
}.getOrElse { LinkPromptState(visible=true,error=true) }
