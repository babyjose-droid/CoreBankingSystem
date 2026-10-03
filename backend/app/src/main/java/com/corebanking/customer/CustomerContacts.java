package com.corebanking.customer;

import com.corebanking.kernel.PiiCipher;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A customer's contact details in clear, for the one purpose that needs them: sending the customer a message or
 * handing the payer's details to a payment gateway (integration module). The values are decrypted in memory for
 * the call and must not be logged, stored in clear or returned by an API; callers keep the masked form.
 */
@Component
public class CustomerContacts {

    /** @param mobile ten digits, or null; {@code email} may be null */
    public record Contact(UUID customerId, String displayName, String mobile, String email, String status) {
        @Override
        public String toString() {
            return "Contact[" + customerId + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final PiiKeys keys;

    public CustomerContacts(JdbcTemplate jdbc, PiiKeys keys) {
        this.jdbc = jdbc;
        this.keys = keys;
    }

    /** @throws ApiException 404 when there is no such customer */
    public Contact of(UUID customerId) {
        PiiCipher cipher = keys.forTenant(CurrentUser.requireTenant());
        List<Contact> l = jdbc.query(
                "SELECT display_name, mobile_cipher, email_cipher, status FROM customer.customer WHERE id = ?",
                (rs, i) -> new Contact(customerId, rs.getString(1), cipher.decrypt(rs.getBytes(2), "customer.mobile"),
                        cipher.decrypt(rs.getBytes(3), "customer.email"), rs.getString(4)), customerId);
        if (l.isEmpty()) throw ApiException.notFound("customer " + customerId);
        return l.get(0);
    }
}
