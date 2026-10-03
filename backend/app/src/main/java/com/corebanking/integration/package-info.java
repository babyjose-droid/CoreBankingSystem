/**
 * Integrations (P2-2): provider framework, payouts, gateway collections, e-mandates and NACH files, signed
 * webhooks, API clients, SMS and e-mail. Pure rules live in backend/integration-core; this module adds
 * persistence, maker-checker, the outbox relay and the HTTP edge.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Integration")
package com.corebanking.integration;
