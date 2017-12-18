// Contains helper functions to convert from api ChatMessage entity to domain entity
@file:JvmName("APIChatMessageConverter")
package org.moire.ultrasonic.data

import org.moire.ultrasonic.domain.ChatMessage
import org.moire.ultrasonic.api.subsonic.models.ChatMessage as ApiChatMessage

fun ApiChatMessage.toDomainEntity(): ChatMessage = ChatMessage().apply {
    username = this@toDomainEntity.username
    time = this@toDomainEntity.time
    message = this@toDomainEntity.message
}

fun List<ApiChatMessage>.toDomainEntitiesList(): List<ChatMessage> = this
        .map { it.toDomainEntity() }
