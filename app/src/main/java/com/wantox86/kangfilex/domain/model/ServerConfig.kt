package com.wantox86.kangfilex.domain.model

data class ServerConfig(
    val id: String,
    val label: String,
    val protocol: ProtocolType,
    val host: String,
    val port: Int,
    val rootPath: String = "/",
    val username: String? = null,
    val authType: AuthType = AuthType.PASSWORD,
    val shareName: String? = null,
    val passiveMode: Boolean = true
)
