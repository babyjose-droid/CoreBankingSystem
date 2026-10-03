package com.corebanking.integration.core.provider;

import com.corebanking.kernel.Masking;

/**
 * Sends an SMS. In India every commercial SMS goes through the operators' DLT platforms (TRAI TCCCPR 2018): the
 * sender (principal entity) is registered, the header (sender id) is registered, and the text must match a
 * registered content template. The three ids travel with every message.
 */
public interface SmsSender {

    /**
     * @param to            mobile number, 10 digits or with country code
     * @param dltEntityId   the tenant's principal entity id on the DLT platform
     * @param dltTemplateId the registered content template this text was rendered from
     * @param header        the registered header (sender id)
     * @param reference     our message id, for idempotency and for matching delivery reports
     */
    record Sms(String to, String text, String dltEntityId, String dltTemplateId, String header, String reference) {
        @Override
        public String toString() {
            return "Sms[" + reference + ", to=" + Masking.mobile(to) + "]";
        }
    }

    MessageResult send(Sms sms);
}
