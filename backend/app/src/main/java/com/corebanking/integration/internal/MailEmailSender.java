package com.corebanking.integration.internal;

import com.corebanking.integration.core.provider.EmailSender;
import com.corebanking.integration.core.provider.MessageResult;
import com.corebanking.platform.Mail;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The SMTP e-mail provider ({@link com.corebanking.integration.core.provider.ProviderCatalog#SMTP}): a message
 * template's e-mail through the platform's mail relay. Text only, to the customer's own address, about their own loan;
 * never an attachment. A relay that is not configured refuses for good; a relay that fails is retried.
 */
@Component
class MailEmailSender implements EmailSender {

    private final Mail mail;

    MailEmailSender(Mail mail) {
        this.mail = mail;
    }

    @Override
    public MessageResult send(Email email) {
        if (!mail.enabled()) return MessageResult.refused("e-mail is not configured in this deployment", false);
        try {
            mail.send(List.of(email.to()), email.subject(), email.text(), List.of());
            return MessageResult.accepted("SMTP-" + email.reference());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return MessageResult.refused(e.getMessage(), true);
        }
    }
}
