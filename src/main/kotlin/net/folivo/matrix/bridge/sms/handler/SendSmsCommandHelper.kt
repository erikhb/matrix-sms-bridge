package net.folivo.matrix.bridge.sms.handler

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import net.folivo.matrix.bot.config.MatrixBotProperties
import net.folivo.matrix.bridge.sms.SmsBridgeProperties
import net.folivo.matrix.bridge.sms.handler.SendSmsCommandHelper.RoomCreationMode.ALWAYS
import net.folivo.matrix.bridge.sms.handler.SendSmsCommandHelper.RoomCreationMode.AUTO
import net.folivo.matrix.bridge.sms.room.AppserviceRoom
import net.folivo.matrix.bridge.sms.room.RoomMessage
import net.folivo.matrix.bridge.sms.room.SmsMatrixAppserviceRoomService
import net.folivo.matrix.restclient.MatrixClient
import net.folivo.matrix.restclient.api.rooms.Preset.TRUSTED_PRIVATE
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit


@Component
class SendSmsCommandHelper(
        private val roomService: SmsMatrixAppserviceRoomService,
        private val matrixClient: MatrixClient,
        private val botProperties: MatrixBotProperties,
        private val smsBridgeProperties: SmsBridgeProperties
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(this::class.java)
    }

    enum class RoomCreationMode {
        AUTO, ALWAYS, NO
    }

    suspend fun createRoomAndSendMessage(
            body: String?,
            sender: String,
            receiverNumbers: List<String>,
            roomName: String?,
            sendAfterLocal: LocalDateTime?,
            roomCreationMode: RoomCreationMode
    ): String {
        val receiverIds = receiverNumbers.map { "@sms_${it.removePrefix("+")}:${botProperties.serverName}" }
        val membersWithoutBot = setOf(sender, *receiverIds.toTypedArray())

        val rooms = roomService.getRoomsWithUsers(membersWithoutBot)
                .take(2)
                .toList()

        val sendAfter = sendAfterLocal?.atZone(ZoneId.of(smsBridgeProperties.defaultTimeZone))?.toInstant()
        try {
            val answer = if (rooms.isEmpty() && roomCreationMode == AUTO || roomCreationMode == ALWAYS) {
                LOG.debug("create room")
                val createdRoomId = matrixClient.roomsApi.createRoom(
                        name = roomName,
                        invite = membersWithoutBot,
                        preset = TRUSTED_PRIVATE
                )

                if (!body.isNullOrBlank()) {
                    val room = roomService.getOrCreateRoom(createdRoomId)
                    sendMessageToRoom(room, sender, body, receiverIds, sendAfter, sendAfterLocal)
                }
                smsBridgeProperties.templates.botSmsSendCreatedRoomAndSendMessage
            } else if (rooms.size == 1) {
                LOG.debug("only send message")
                val room = roomService.getOrCreateRoom(rooms[0].roomId)
                if (body.isNullOrBlank()) {
                    smsBridgeProperties.templates.botSmsSendNoMessage
                } else {
                    val botIsMember = room.members.find { it.member.userId == "@${botProperties.username}:${botProperties.serverName}" } != null
                    val expectedManagedMemberSize = if (botIsMember) receiverIds.size + 1 else receiverIds.size
                    val membersMatch = room.members.count { it.member.isManaged } == expectedManagedMemberSize
                    if (membersMatch) {
                        if (!botIsMember) {
                            LOG.debug("try to invite sms bot user to room ${room.roomId} and send message later")
                            matrixClient.roomsApi.inviteUser(
                                    roomId = room.roomId,
                                    userId = "@${botProperties.username}:${botProperties.serverName}",
                                    asUserId = receiverIds.first()
                            )
                        }
                        sendMessageToRoom(room, sender, body, receiverIds, sendAfter, sendAfterLocal)

                        smsBridgeProperties.templates.botSmsSendSendMessage
                    } else {
                        smsBridgeProperties.templates.botSmsSendDisabledRoomCreation
                    }
                }
            } else if (rooms.size > 1) {
                smsBridgeProperties.templates.botSmsSendTooManyRooms
            } else {
                smsBridgeProperties.templates.botSmsSendDisabledRoomCreation
            }

            return answer.replace("{receiverNumbers}", receiverNumbers.joinToString())
        } catch (error: Throwable) {
            LOG.warn("trying to create room, join room or send message failed: ${error.message}")
            return smsBridgeProperties.templates.botSmsSendError
                    .replace("{error}", error.message ?: "unknown")
                    .replace("{receiverNumbers}", receiverNumbers.joinToString())
        }
    }

    private suspend fun sendMessageToRoom(
            room: AppserviceRoom,
            sender: String,
            body: String,
            receiverIds: List<String>,
            sendAfter: Instant?,
            sendAfterLocal: LocalDateTime?
    ) {
        if (sendAfter != null && Instant.now().until(sendAfter, ChronoUnit.SECONDS) > 15) {
            LOG.debug("notify room ${room.roomId} that message will be send later")
            roomService.sendRoomMessage(
                    RoomMessage(
                            room = room,
                            body = smsBridgeProperties.templates.botSmsSendNoticeDelayedMessage
                                    .replace("{sendAfter}", sendAfterLocal.toString()),
                            requiredReceiverIds = receiverIds.toSet(),
                            isNotice = true
                    )
            )
        }
        LOG.debug("send message to room ${room.roomId}")
        roomService.sendRoomMessage(
                RoomMessage(
                        room = room,
                        body = smsBridgeProperties.templates.botSmsSendNewRoomMessage
                                .replace("{sender}", sender)
                                .replace("{body}", body),
                        requiredReceiverIds = receiverIds.toSet(),
                        sendAfter = sendAfter ?: Instant.now()
                )
        )
    }

}