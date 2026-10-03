package com.corebanking.eod;

import java.time.LocalDate;

/**
 * Told when end of day has completed and the next business date is open, so a module can do what had to wait
 * for the new date without the EOD module knowing about it (lending: book the receipts accepted during end of day,
 * US-111). Called on the EOD worker thread with the tenant bound and no user; a listener that throws is logged and
 * does not affect the completed run. Work done here must also be reachable some other way (a job), because the
 * application may stop between the date roll and this call.
 */
public interface EodListener {

    void businessDateOpened(String tenant, LocalDate closed, LocalDate opened);
}
