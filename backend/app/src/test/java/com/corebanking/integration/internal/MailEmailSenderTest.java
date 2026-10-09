package com.corebanking.integration.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.integration.core.provider.EmailSender;
import com.corebanking.integration.core.provider.ProviderCatalog;
import com.corebanking.integration.core.provider.ProviderKind;
import com.corebanking.platform.Mail;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailEmailSenderTest {

    static final class Recorder implements Mail {
        final boolean on;
        final boolean fail;
        final List<List<String>> to = new ArrayList<>();
        final List<Integer> attachments = new ArrayList<>();

        Recorder(boolean on, boolean fail) {
            this.on = on;
            this.fail = fail;
        }

        @Override public boolean enabled() { return on; }

        @Override public void send(List<String> recipients, String subject, String text, List<Attachment> files) {
            if (fail) throw new IllegalStateException("the mail relay did not accept the message (MailSendException)");
            to.add(recipients);
            attachments.add(files.size());
        }
    }

    static final EmailSender.Email EMAIL = new EmailSender.Email("customer@claude-test.example", "Payment received", "CLAUDE-TEST text",
            "no-reply@claude-test.example", "M1");

    @Test
    void sends_the_text_to_the_customer_only_and_never_an_attachment() {
        Recorder mail = new Recorder(true, false);
        var r = new MailEmailSender(mail).send(EMAIL);
        assertTrue(r.accepted());
        assertEquals("SMTP-M1", r.providerRef());
        assertEquals(List.of(List.of("customer@claude-test.example")), mail.to);
        assertEquals(List.of(0), mail.attachments);
    }

    @Test
    void no_relay_refuses_for_good_and_a_failing_relay_is_retried() {
        var off = new MailEmailSender(new Recorder(false, false)).send(EMAIL);
        assertFalse(off.accepted());
        assertFalse(off.retryable());
        var failing = new MailEmailSender(new Recorder(true, true)).send(EMAIL);
        assertFalse(failing.accepted());
        assertTrue(failing.retryable());
        assertFalse(failing.error().contains("@"), "no address in the error");
    }

    @Test
    void smtp_is_an_e_mail_provider_the_application_binds() {
        assertEquals(ProviderCatalog.SMTP, ProviderCatalog.spec(ProviderKind.EMAIL, ProviderCatalog.SMTP).code());
        assertThrows(IllegalArgumentException.class, () -> ProviderCatalog.email(ProviderCatalog.SMTP));
    }
}
