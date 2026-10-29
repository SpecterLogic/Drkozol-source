package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackupArchiveCodecTest {
    @Test fun streamedBackupsInteroperateAndNeverKeepUnauthenticatedOutput() {
        val directory = java.nio.file.Files.createTempDirectory("backup-codec").toFile()
        try {
            val input = java.io.File(directory, "input").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
            val encoded = java.io.File(directory, "protected")
            val restored = java.io.File(directory, "restored")
            val password = "sample-password".toCharArray()
            BackupArchiveCodec.encodeFile(input, encoded, password)
            assertArrayEquals(input.readBytes(), BackupArchiveCodec.decode(encoded.readBytes(), password))
            BackupArchiveCodec.decodeFile(encoded, restored, password)
            assertArrayEquals(input.readBytes(), restored.readBytes())
            assertTrue(runCatching { BackupArchiveCodec.decodeFile(encoded, restored, "incorrect".toCharArray()) }.isFailure)
            assertFalse(restored.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun passwordBackupAuthenticatesAndRemainsPortable() {
        val zip = "PK-test-backup".toByteArray()
        val password = "sample-password".toCharArray()
        val encrypted = BackupArchiveCodec.encode(zip, password)
        assertTrue(BackupArchiveCodec.encrypted(encrypted))
        assertFalse(encrypted.contentEquals(BackupArchiveCodec.encode(zip, password)))
        assertArrayEquals(zip, BackupArchiveCodec.decode(encrypted, password))
        assertTrue(runCatching { BackupArchiveCodec.decode(encrypted, "incorrect".toCharArray()) }.isFailure)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { BackupArchiveCodec.decode(encrypted, password) }.isFailure)
    }

    @Test fun legacyAndInvalidInputsHaveExplicitBehavior() {
        val zip = "PK-legacy".toByteArray()
        assertArrayEquals(zip, BackupArchiveCodec.decode(zip, charArrayOf()))
        assertTrue(runCatching { BackupArchiveCodec.decode(zip, "sample-password".toCharArray()) }.isFailure)
        assertArrayEquals(zip, BackupArchiveCodec.encode(zip, charArrayOf()))
        assertTrue(runCatching { BackupArchiveCodec.encode(zip, "short".toCharArray()) }.isFailure)
        val encrypted = BackupArchiveCodec.encode(zip, "sample-password".toCharArray())
        assertTrue(runCatching { BackupArchiveCodec.decode(encrypted, charArrayOf()) }.isFailure)
        assertTrue(runCatching { BackupArchiveCodec.decode(encrypted.copyOf(20), "sample-password".toCharArray()) }.isFailure)
    }
}
