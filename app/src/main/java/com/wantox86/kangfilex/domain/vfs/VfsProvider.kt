package com.wantox86.kangfilex.domain.vfs

import com.wantox86.kangfilex.domain.model.VfsFile
import java.io.InputStream
import java.io.OutputStream

interface VfsProvider {
    val providerId: String
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun isConnected(): Boolean
    suspend fun listFiles(path: String): Result<List<VfsFile>>
    suspend fun openInputStream(path: String): InputStream
    suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream
    suspend fun createDirectory(path: String): Result<Unit>
    suspend fun delete(path: String, recursive: Boolean): Result<Unit>
    suspend fun rename(oldPath: String, newPath: String): Result<Unit>
    suspend fun exists(path: String): Boolean
}
