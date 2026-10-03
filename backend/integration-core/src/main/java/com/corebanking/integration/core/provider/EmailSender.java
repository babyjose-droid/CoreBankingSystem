package com.corebanking.integration.core.provider;

import com.corebanking.kernel.Masking;

/** Sends a plain-text e-mail. */
public interface EmailSender {

    record Email(String to, String subject, String text, String fromAddress, String reference) {
        @Override
        public String toString() {
            return "Email[" + reference + ", to=" + Masking.email(to) + "]";
        }
    }

    MessageResult send(Email email);
}
