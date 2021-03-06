package net.folivo.matrix.bridge.sms.message

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import net.folivo.matrix.bot.membership.MatrixMembershipService
import net.folivo.matrix.bot.user.MatrixUserService
import net.folivo.matrix.core.model.MatrixId.RoomId
import net.folivo.matrix.core.model.MatrixId.UserId
import net.folivo.matrix.core.model.events.m.room.message.NoticeMessageEventContent
import net.folivo.matrix.core.model.events.m.room.message.TextMessageEventContent
import net.folivo.matrix.restclient.MatrixClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class MatrixMessageService(
    private val messageRepository: MatrixMessageRepository,
    private val messageReceiverRepository: MatrixMessageReceiverRepository,
    private val membershipService: MatrixMembershipService,
    private val userService: MatrixUserService,
    private val matrixClient: MatrixClient
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(this::class.java)
    }

    suspend fun sendRoomMessage(message: MatrixMessage, requiredMembers: Set<UserId> = setOf()) {
        val isNew = message.id == null

        if (Instant.now().isAfter(message.sendAfter)) {
            val roomId = message.roomId
            val requiredMembersAndSender =
                if (message.asUserId == null) requiredMembers
                else setOf(*requiredMembers.toTypedArray(), message.asUserId)
            val containsReceiversAndSender = membershipService.doesRoomContainsMembers(roomId, requiredMembersAndSender)
            if (containsReceiversAndSender) {
                try {
                    LOG.debug("send cached message to room $roomId and delete from db")
                    matrixClient.roomsApi.sendRoomEvent(
                        roomId = roomId,
                        eventContent = if (message.isNotice) NoticeMessageEventContent(message.body)
                        else TextMessageEventContent(message.body),
                        asUserId = message.asUserId
                    )
                    deleteMessage(message)
                } catch (error: Throwable) {
                    LOG.debug(
                        "Could not send cached message to room $roomId. This happens e.g. when the bot was kicked " +
                                "out of the room, before the required receivers did join. Error: ${error.message}"
                    )
                    if (message.sendAfter.until(Instant.now(), ChronoUnit.DAYS) > 3) {
                        LOG.warn(
                            "We have cached messages for the room $roomId, but sending the message " +
                                    "didn't worked since 3 days. " +
                                    "This usually should never happen! The message will now be deleted."
                        )
                        deleteMessage(message)
                        // TODO directly notify user
                    }
                }
            } else if (isNew) {
                saveMessageAndReceivers(message.copy(sendAfter = Instant.now()), requiredMembers)
            } else if (message.sendAfter.until(Instant.now(), ChronoUnit.DAYS) > 3) {
                LOG.warn(
                    "We have cached messages for the room $roomId, but the required receivers " +
                            "${requiredMembers.joinToString()} didn't join since 3 days. " +
                            "This usually should never happen! The message will now be deleted."
                )
                deleteMessage(message)
                // TODO directly notify user
            } else {
                LOG.debug("wait for required receivers to join")
            }
        } else if (isNew) {
            saveMessageAndReceivers(message, requiredMembers)
        }
    }

    suspend fun saveMessageAndReceivers(message: MatrixMessage, requiredMembers: Set<UserId>) {
        if (message.asUserId != null) userService.getOrCreateUser(message.asUserId)
        val savedMessage = messageRepository.save(message)
        requiredMembers.forEach {
            if (savedMessage.id != null) {
                userService.getOrCreateUser(it)
                messageReceiverRepository.save(MatrixMessageReceiver(savedMessage.id, it))
            }
        }
    }

    suspend fun deleteMessage(message: MatrixMessage) {
        if (message.id != null) messageRepository.delete(message)
    }

    suspend fun deleteByRoomId(roomId: RoomId) {
        messageRepository.deleteByRoomId(roomId)
    }

    suspend fun processMessageQueue() {
        messageRepository.findAll()
            .collect { message ->
                if (message.id != null) {
                    val requiredReceivers = messageReceiverRepository.findByRoomMessageId(message.id)
                        .map { it.userId }
                        .toSet()
                    sendRoomMessage(message, requiredReceivers)
                }
            }
    }
}