package com.corebanking.integration.internal;

/**
 * Background work of the integrations, run for each tenant by {@link IntegrationWorker} with the tenant bound to
 * the thread. A task claims a small batch, does it and returns; it must be safe to run on several app instances
 * at once (rows are claimed with SKIP LOCKED and a lease) and to be interrupted at any point.
 */
interface IntegrationTask {

    String name();

    void run();
}
