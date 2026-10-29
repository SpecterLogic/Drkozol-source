package com.v2ray.ang.handler

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.io.DataInputStream

/** Password-protected export format; independent of device keys so backups remain portable. */
object BackupArchiveCodec {
    private val magic = "DRKOZOL-BACKUP-1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 1_400_000
    const val MAX_BYTES = 64 * 1024 * 1024

    fun encrypted(data: ByteArray): Boolean = data.size >= magic.size &&
        magic.indices.all { data[it] == magic[it] }

    fun encode(data: ByteArray, password: CharArray): ByteArray {
        require(data.size <= MAX_BYTES)
        if (password.isEmpty()) return data
        require(password.size >= 8)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + nonce
        return header + crypt(Cipher.ENCRYPT_MODE, data, password, salt, nonce, header)
    }

    fun decode(data: ByteArray, password: CharArray): ByteArray {
        require(data.size <= MAX_BYTES + 128)
        if (!encrypted(data)) {
            require(password.isEmpty()) // Do not silently downgrade a password-authenticated restore.
            return data // Existing unencrypted ZIP backups remain readable with an empty password.
        }
        val headerSize = magic.size + 28
        require(data.size >= headerSize + 16 && password.isNotEmpty())
        val header = data.copyOfRange(0, headerSize)
        val salt = data.copyOfRange(magic.size, magic.size + 16)
        val nonce = data.copyOfRange(magic.size + 16, headerSize)
        return crypt(Cipher.DECRYPT_MODE, data.copyOfRange(headerSize, data.size), password, salt, nonce, header)
    }

    fun encodeFile(input: File, output: File, password: CharArray) {
        require(input.length() <= MAX_BYTES && password.size >= 8)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + nonce
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce, header)
        output.outputStream().use { destination ->
            destination.write(header)
            input.inputStream().use { source -> transform(source, destination, cipher) }
        }
    }

    fun decodeFile(input: File, output: File, password: CharArray) {
        require(input.length() <= MAX_BYTES + 128)
        try {
            DataInputStream(input.inputStream()).use { source ->
                val prefix = ByteArray(minOf(input.length(), magic.size.toLong()).toInt())
                source.readFully(prefix)
                if (!prefix.contentEquals(magic)) {
                    require(password.isEmpty())
                    output.outputStream().use { it.write(prefix); source.copyTo(it) }
                    return
                }
                require(input.length() >= magic.size + 44 && password.isNotEmpty())
                val salt = ByteArray(16).also(source::readFully)
                val nonce = ByteArray(12).also(source::readFully)
                val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce, magic + salt + nonce)
                // The temporary output is never restored until the GCM tag has been checked.
                output.outputStream().use { destination -> transform(source, destination, cipher) }
            }
            require(output.length() <= MAX_BYTES)
        } catch (error: Exception) { output.delete(); throw error }
    }

    private fun transform(source: java.io.InputStream, destination: java.io.OutputStream, cipher: Cipher) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = source.read(buffer); if (count < 0) break
            cipher.update(buffer, 0, count)?.let(destination::write)
        }
        // Call explicitly: stream wrappers can suppress authentication errors on some providers.
        destination.write(cipher.doFinal())
    }

    private fun crypt(mode: Int, data: ByteArray, password: CharArray, salt: ByteArray,
        nonce: ByteArray, header: ByteArray): ByteArray = cipher(mode, password, salt, nonce, header).doFinal(data)

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        // HMAC-SHA1 PBKDF2 is available on Android 24/25 as well; its high work factor
        // avoids requiring a newer provider to restore the same portable archive.
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
                this
            }
        } finally { key.fill(0) }
    }
}
