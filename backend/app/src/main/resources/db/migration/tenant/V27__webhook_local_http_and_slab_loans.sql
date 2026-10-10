-- V27: (1) A webhook URL may be http as well as https at the database level.
-- The database cannot know which hosts a deployment trusts, so the scheme rule lives where the host rule lives: the
-- application's EndpointGuard. It accepts http only for host names on corebanking.integration.webhook.local-allow-hosts
-- (empty unless the local compose sets it), checks that at proposal AND again before every delivery, so an http URL
-- that reached this table in any other way is never called. Everything else must still be https on a public name.
ALTER TABLE integration.webhook_endpoint DROP CONSTRAINT webhook_endpoint_url_check;
ALTER TABLE integration.webhook_endpoint
  ADD CONSTRAINT webhook_endpoint_url_check CHECK (url ~ '^https?://[^[:space:]]+$' AND length(url) <= 2000);
