package cn.gmzc.mail;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** 云端编译用 ABI 桩：签名与服务器内 GMZCMail 的公开服务完全一致，不打包进 jar。 */
public interface MailService {
    UUID SYSTEM_SENDER_ID = new UUID(0L, 0L);

    UUID sendMail(
        UUID senderId,
        String senderName,
        UUID recipientId,
        String recipientName,
        String body,
        Collection<ItemStack> attachments
    );

    default UUID sendSystemMail(
        String senderName,
        UUID recipientId,
        String recipientName,
        String body,
        Collection<ItemStack> attachments
    ) {
        return sendMail(
            SYSTEM_SENDER_ID,
            senderName,
            recipientId,
            recipientName,
            body,
            attachments == null ? List.of() : attachments
        );
    }

    boolean hasUnreadMail(UUID playerId);
}
