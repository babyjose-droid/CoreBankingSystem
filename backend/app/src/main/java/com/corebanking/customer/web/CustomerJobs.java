package com.corebanking.customer.web;

import com.corebanking.platform.JobHandler;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Customer housekeeping jobs (US-112), both thin calls to database functions that carry the rules:
 * <ul>
 *   <li>KYC_EXPIRY: {@code customer.expire_kyc(business date)} moves customers whose KYC documents have run out
 *       from VERIFIED to EXPIRED (V17).</li>
 *   <li>CONSENT_EXPIRY: {@code customer.expire_consents()} records in the consent history each consent whose
 *       validity has ended (V19). {@code customer.has_consent} already treats such a consent as not in force.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class CustomerJobs {

    @Bean
    JobHandler kycExpiryJob(JdbcTemplate jdbc) {
        return new JobHandler() {
            @Override public String kind() { return "KYC_EXPIRY"; }

            @Override
            public Result run(Context context) {
                LocalDate day = jdbc.queryForObject("SELECT business_date FROM platform.business_day WHERE id = 1", LocalDate.class);
                Integer expired = jdbc.queryForObject("SELECT customer.expire_kyc(?)", Integer.class, day);
                return new Result(expired == null ? 0 : expired, 0, Map.of("businessDate", String.valueOf(day)));
            }
        };
    }

    @Bean
    JobHandler consentExpiryJob(JdbcTemplate jdbc) {
        return new JobHandler() {
            @Override public String kind() { return "CONSENT_EXPIRY"; }

            @Override
            public Result run(Context context) {
                Integer expired = jdbc.queryForObject("SELECT customer.expire_consents()", Integer.class);
                return Result.of(expired == null ? 0 : expired);
            }
        };
    }
}
