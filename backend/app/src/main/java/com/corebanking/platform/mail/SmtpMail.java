package com.corebanking.platform.mail;

import com.corebanking.platform.Mail;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * {@link Mail} over SMTP ({@code spring.mail.*}: host, port, username, password, STARTTLS), with Spring's
 * JavaMailSender. Amazon SES is used through its SMTP interface, so any SMTP relay can replace it. All mail-library
 * code is in this class.
 */
@Component
@ConditionalOnProperty(name = "corebanking.mail.enabled", havingValue = "true")
class SmtpMail implements Mail {

    private final JavaMailSender sender;
    private final String from;

    SmtpMail(JavaMailSender sender, @Value("${corebanking.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void send(List<String> to, String subject, String text, List<Attachment> attachments) {
        if (to == null || to.isEmpty()) throw new IllegalArgumentException("no recipient");
        try {
            MimeMessage message = sender.createMimeMessage();
            boolean multipart = attachments != null && !attachments.isEmpty();
            MimeMessageHelper m = new MimeMessageHelper(message, multipart, "UTF-8");
            m.setFrom(from);
            m.setTo(to.toArray(String[]::new));
            m.setSubject(subject);
            m.setText(text, false);
            if (multipart) {
                for (Attachment a : attachments) m.addAttachment(a.fileName(), new ByteArrayResource(a.content()), a.contentType());
            }
            sender.send(message);
        } catch (MessagingException | MailException e) {
            // the exception text can quote addresses: only its kind is passed on
            throw new IllegalStateException("the mail relay did not accept the message (" + e.getClass().getSimpleName() + ")");
        }
    }
}
