-- Tamper-evident audit trail: every row carries the hash of the previous row.
CREATE TABLE audit.event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor       text NOT NULL,
    actor_ip    inet,
    action      text NOT NULL,
    entity_type text NOT NULL,
    entity_id   text,
    detail      jsonb,
    prev_hash   bytea,
    row_hash    bytea NOT NULL
);

CREATE FUNCTION audit.chain() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prev bytea;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('audit.event'));
  SELECT row_hash INTO prev FROM audit.event ORDER BY id DESC LIMIT 1;
  NEW.prev_hash := prev;
  NEW.row_hash := sha256(convert_to(
      coalesce(encode(prev,'hex'),'') || '|' || NEW.at::text || '|' || NEW.actor || '|' || NEW.action || '|' ||
      NEW.entity_type || '|' || coalesce(NEW.entity_id,'') || '|' || coalesce(NEW.detail::text,''), 'UTF8'));
  RETURN NEW;
END $$;
CREATE TRIGGER audit_chain BEFORE INSERT ON audit.event FOR EACH ROW EXECUTE FUNCTION audit.chain();
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit.event FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- Verify the whole chain; returns the first broken id or NULL.
CREATE FUNCTION audit.verify_chain() RETURNS bigint LANGUAGE plpgsql STABLE AS $$
DECLARE r record; prev bytea := NULL; expect bytea;
BEGIN
  FOR r IN SELECT * FROM audit.event ORDER BY id LOOP
    expect := sha256(convert_to(
      coalesce(encode(prev,'hex'),'') || '|' || r.at::text || '|' || r.actor || '|' || r.action || '|' ||
      r.entity_type || '|' || coalesce(r.entity_id,'') || '|' || coalesce(r.detail::text,''), 'UTF8'));
    IF r.row_hash <> expect OR r.prev_hash IS DISTINCT FROM prev THEN RETURN r.id; END IF;
    prev := r.row_hash;
  END LOOP;
  RETURN NULL;
END $$;
