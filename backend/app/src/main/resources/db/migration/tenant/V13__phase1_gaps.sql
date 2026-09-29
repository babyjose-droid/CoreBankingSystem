-- Phase 1 gap closure: branch scope as a data filter (US-020), branch sets in staff scope (US-010),
-- Indian states with GST codes and territory upload (US-012).

-- ---- Branch scope (US-020) ---------------------------------------------------------------------
-- A staff user sees: every branch (all_branches), or their home branch + the branches granted directly
-- + the members of the branch sets granted. A user without an ACTIVE staff profile sees nothing.
CREATE TABLE platform.staff_branch_set_scope (
    user_id  text NOT NULL REFERENCES platform.staff_user(user_id),
    set_code text NOT NULL REFERENCES platform.branch_set(code),
    PRIMARY KEY (user_id, set_code)
);

-- Keycloak subject or username (the API passes the username; batch jobs never call this).
CREATE FUNCTION platform.visible_branches(p_user text) RETURNS TABLE (branch_code text)
LANGUAGE sql STABLE AS $$
  WITH u AS (
    SELECT user_id, home_branch, all_branches FROM platform.staff_user
     WHERE (user_id = p_user OR lower(username) = lower(p_user)) AND status = 'ACTIVE'
     ORDER BY (user_id = p_user) DESC LIMIT 1)
  SELECT b.code FROM platform.branch b, u WHERE u.all_branches
  UNION
  SELECT u.home_branch FROM u
  UNION
  SELECT s.branch_code FROM platform.staff_branch_scope s JOIN u ON u.user_id = s.user_id
  UNION
  SELECT m.branch_code FROM platform.staff_branch_set_scope ss JOIN u ON u.user_id = ss.user_id
    JOIN platform.branch_set_member m ON m.set_code = ss.set_code
$$;

CREATE FUNCTION platform.sees_all_branches(p_user text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((SELECT all_branches FROM platform.staff_user
                    WHERE (user_id = p_user OR lower(username) = lower(p_user)) AND status = 'ACTIVE'
                    ORDER BY (user_id = p_user) DESC LIMIT 1), false)
$$;

CREATE FUNCTION platform.can_see_branch(p_user text, p_branch text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT p_branch IS NOT NULL AND EXISTS (SELECT 1 FROM platform.visible_branches(p_user) v WHERE v.branch_code = p_branch)
$$;

-- ---- Territory (US-012) ------------------------------------------------------------------------
-- India and its states / union territories with GST state codes (CBIC list). Branch state codes are GST codes.
INSERT INTO platform.country (code, name) VALUES ('IN', 'India') ON CONFLICT (code) DO NOTHING;
INSERT INTO platform.state (code, country_code, name, gst_state_code, is_union_territory) VALUES
  ('JK','IN','Jammu and Kashmir','01',true),  ('HP','IN','Himachal Pradesh','02',false),
  ('PB','IN','Punjab','03',false),             ('CH','IN','Chandigarh','04',true),
  ('UK','IN','Uttarakhand','05',false),        ('HR','IN','Haryana','06',false),
  ('DL','IN','Delhi','07',true),               ('RJ','IN','Rajasthan','08',false),
  ('UP','IN','Uttar Pradesh','09',false),      ('BR','IN','Bihar','10',false),
  ('SK','IN','Sikkim','11',false),             ('AR','IN','Arunachal Pradesh','12',false),
  ('NL','IN','Nagaland','13',false),           ('MN','IN','Manipur','14',false),
  ('MZ','IN','Mizoram','15',false),            ('TR','IN','Tripura','16',false),
  ('ML','IN','Meghalaya','17',false),          ('AS','IN','Assam','18',false),
  ('WB','IN','West Bengal','19',false),        ('JH','IN','Jharkhand','20',false),
  ('OD','IN','Odisha','21',false),             ('CG','IN','Chhattisgarh','22',false),
  ('MP','IN','Madhya Pradesh','23',false),     ('GJ','IN','Gujarat','24',false),
  ('DH','IN','Dadra and Nagar Haveli and Daman and Diu','26',true),
  ('MH','IN','Maharashtra','27',false),        ('KA','IN','Karnataka','29',false),
  ('GA','IN','Goa','30',false),                ('LD','IN','Lakshadweep','31',true),
  ('KL','IN','Kerala','32',false),             ('TN','IN','Tamil Nadu','33',false),
  ('PY','IN','Puducherry','34',true),          ('AN','IN','Andaman and Nicobar Islands','35',true),
  ('TS','IN','Telangana','36',false),          ('AP','IN','Andhra Pradesh','37',false),
  ('LA','IN','Ladakh','38',true),              ('OT','IN','Other Territory','97',true)
ON CONFLICT (code) DO NOTHING;

-- One row per pincode + city. Lookup by pincode returns every city it serves (a pincode can span places).
CREATE VIEW platform.pincode_lookup AS
  SELECT p.pincode, c.name AS city, d.name AS district, s.code AS state_code, s.name AS state_name,
         s.gst_state_code, s.country_code
    FROM platform.pincode p
    JOIN platform.city c ON c.id = p.city_id
    JOIN platform.district d ON d.id = c.district_id
    JOIN platform.state s ON s.code = d.state_code;

-- Loads territory rows [{state, district, city, pincode}] where state is the state code or GST code.
-- Idempotent: existing districts, cities and pincodes are reused. All-or-nothing: the first bad row
-- aborts the whole load with its row number.
CREATE FUNCTION platform.load_territory(rows jsonb) RETURNS int LANGUAGE plpgsql AS $$
DECLARE r jsonb; n int := 0; st text; did int; cid int; added int := 0;
BEGIN
  FOR r IN SELECT * FROM jsonb_array_elements(rows) LOOP
    n := n + 1;
    SELECT code INTO st FROM platform.state
     WHERE code = upper(trim(r->>'state')) OR gst_state_code = lpad(trim(r->>'state'), 2, '0');
    IF st IS NULL THEN
      RAISE EXCEPTION 'row %: unknown state "%"', n, r->>'state' USING ERRCODE = '22023';
    END IF;
    IF coalesce(trim(r->>'district'), '') = '' OR coalesce(trim(r->>'city'), '') = '' THEN
      RAISE EXCEPTION 'row %: district and city are required', n USING ERRCODE = '22023';
    END IF;
    IF coalesce(trim(r->>'pincode'), '') !~ '^[1-9][0-9]{5}$' THEN
      RAISE EXCEPTION 'row %: pincode "%" is not six digits', n, r->>'pincode' USING ERRCODE = '22023';
    END IF;
    INSERT INTO platform.district (state_code, name) VALUES (st, initcap(trim(r->>'district')))
      ON CONFLICT (state_code, name) DO NOTHING;
    SELECT id INTO did FROM platform.district WHERE state_code = st AND name = initcap(trim(r->>'district'));
    INSERT INTO platform.city (district_id, name) VALUES (did, initcap(trim(r->>'city')))
      ON CONFLICT (district_id, name) DO NOTHING;
    SELECT id INTO cid FROM platform.city WHERE district_id = did AND name = initcap(trim(r->>'city'));
    INSERT INTO platform.pincode (pincode, city_id) VALUES (trim(r->>'pincode'), cid) ON CONFLICT DO NOTHING;
    IF FOUND THEN added := added + 1; END IF;
  END LOOP;
  RETURN added;
END $$;

-- ---- Enumerations and system properties (US-013) ----------------------------------------------
ALTER TABLE platform.enumeration ADD CONSTRAINT enumeration_code_format CHECK (code ~ '^[A-Z0-9_]{1,40}$');
ALTER TABLE platform.enumeration ADD CONSTRAINT enumeration_type_format CHECK (enum_type ~ '^[A-Z][A-Z0-9_]{1,40}$');
ALTER TABLE platform.system_property ADD COLUMN description text;
