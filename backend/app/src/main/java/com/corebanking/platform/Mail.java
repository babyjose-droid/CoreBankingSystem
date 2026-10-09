package com.corebanking.platform;

import java.util.List;

/**
 * Outgoing e-mail of the platform: scheduled report files, end-of-day alerts, and the EMAIL channel of message
 * templates when a tenant chooses the SMTP provider. Sent through the deployment's SMTP relay (Amazon SES in the
 * cloud tiers, any SMTP server elsewhere; Mailpit locally). Without a relay ({@code corebanking.mail.enabled=false})
 * nothing is sent and {@link #enabled()} says so. Implementations never log addresses or content.
 */
public interface Mail {

    record Attachment(String fileName, String contentType, byte[] content) {}

    /** False when the deployment has no SMTP relay: callers record the message as not sent. */
    boolean enabled();

    /**
     * Sends one message to the recipients (all in To). Attachments are for internal staff only: callers keep files
     * to addresses that pass {@link InternalRecipients}.
     *
     * @throws IllegalStateException when the relay is not configured or refuses the message (no address in the text)
     */
    void send(List<String> to, String subject, String text, List<Attachment> attachments);
}
