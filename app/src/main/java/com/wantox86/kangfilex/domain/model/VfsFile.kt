package com.wantox86.kangfilex.domain.model

data class VfsFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val providerId: String,
    val isSymlink: Boolean = false,
    val childDirCount: Int? = null,
    val childFileCount: Int? = null
)
