package com.corebanking.platform.mail;

import com.corebanking.platform.Mail;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** {@link Mail} when the deployment has no SMTP relay ({@code corebanking.mail.enabled} not true): nothing is sent. */
@Component
@ConditionalOnProperty(name = "corebanking.mail.enabled", havingValue = "false", matchIfMissing = true)
class NoMail implements Mail {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void send(List<String> to, String subject, String text, List<Attachment> attachments) {
        throw new IllegalStateException("e-mail is not configured in this deployment (corebanking.mail.enabled)");
    }
}
