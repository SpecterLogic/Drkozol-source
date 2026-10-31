package com.v2ray.ang.handler

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire-compatible crypt1 obfuscation, NOT secret storage: its interoperability key is
 * public in @incy/link-encoder (MIT). See docs/CRYPT1-NOTICE.txt and public test vectors.
 */
object SubscriptionLinkCodec {
    data class Decoded(val url: String, val name: String?)
    private val key = "f6d40ea0c8a8899d7c682d09ba0d4165dfe2b3dd45e6bb3e25cb233cf00c2462".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun isLink(value: String): Boolean = value.trim().let { it.startsWith("drkozol://crypt1/") || it.startsWith("incy://crypt1/") }
    fun decode(value: String): Decoded? = runCatching {
        require(isLink(value) && value.length <= 20_000)
        val bytes = Base64.getUrlDecoder().decode(value.trim().substringAfter("://crypt1/").trimEnd('/'))
        require(bytes.size >= 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        val json = JsonParser.parseString(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8)).asJsonObject
        require(json["v"]?.asInt == 1)
        val url = json["url"].asString
        require(validUrl(url))
        val name = json["n"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.let( com.v2ray.ang.dto.entities.SubscriptionMetadata::text )?.take(128)
        Decoded(url, name)
    }.getOrNull()
    fun encode(url: String, name: String? = null): String {
        require(validUrl(url))
        val json = JsonObject().apply { if (!name.isNullOrBlank()) addProperty("n",name.take(128)); addProperty("url",url); addProperty("v",1) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv))
        return "drkozol://crypt1/"+Base64.getUrlEncoder().withoutPadding().encodeToString(iv+cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
    }
    fun share(provider: com.v2ray.ang.dto.entities.SubscriptionItem): String =
        if (provider.obfuscatedShare) encode(provider.url, provider.remarks) else provider.url
    private fun validUrl(value: String): Boolean = runCatching {
        val uri=URI(value); value.length<=8192 && uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null
    }.getOrDefault(false)
}
