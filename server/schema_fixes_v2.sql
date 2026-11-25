-- =====================================================================
--  DroneRadar — review fixes v2 (run once, after schema_global_v1.sql)
--  * operator_id_ok: verifies the Luhn-36 check char when the secret part was broadcast
--    (dash optional), treats the literal 'NULL' as "not declared" — same rules as the phone.
--  * spoof_check jump test: time between the two positions from the phones' seen_at clocks
--    (not upload time), and a speed-derived gate: dist > 70 m/s × seconds + 50 m.
-- =====================================================================

create or replace function public.luhn36(p text)
returns text
language plpgsql immutable as $$
declare a constant text := '0123456789abcdefghijklmnopqrstuvwxyz';
        factor int := 2; total int := 0; cp int; add int; i int;
begin
  for i in reverse length(p)..1 loop
    cp := strpos(a, substr(p, i, 1)) - 1;
    if cp < 0 then return null; end if;          -- not computable, not "wrong"
    add := factor * cp;
    factor := case when factor = 2 then 1 else 2 end;
    total := total + add / 36 + add % 36;
  end loop;
  return substr(a, ((36 - total % 36) % 36) + 1, 1);
end $$;

create or replace function public.operator_id_ok(p text)
returns boolean
language plpgsql immutable as $$
declare id text := btrim(coalesce(p, ''), E' \\t\\n\\r'); m text[]; c text; sum text;
begin
  if id = '' or upper(id) = 'NULL' then return true; end if;            -- not declared
  if upper(id) ~ '^FA[0-9A-Z]{8}$' then return true; end if;            -- FAA registration
  if length(id) between 16 and 20 and left(id, 3) ~ '^[A-Za-z]{3}$' then
    id := upper(left(id, 3)) || lower(substr(id, 4));
  end if;
  -- with the secret part (19 or 20 chars): the checksum can be verified
  m := regexp_match(id, '^([A-Z]{3})([0-9a-z]{12})([0-9a-z])-?([0-9a-z]{3})$');
  if m is not null then
    if not (m[1] = any (array['ABW','AFG','AGO','AIA','ALA','ALB','AND','ARE','ARG','ARM','ASM','ATA','ATF','ATG','AUS','AUT','AZE','BDI','BEL','BEN','BES','BFA','BGD','BGR','BHR','BHS','BIH','BLM','BLR','BLZ','BMU','BOL','BRA','BRB','BRN','BTN','BVT','BWA','CAF','CAN','CCK','CHE','CHL','CHN','CIV','CMR','COD','COG','COK','COL','COM','CPV','CRI','CUB','CUW','CXR','CYM','CYP','CZE','DEU','DJI','DMA','DNK','DOM','DZA','ECU','EGY','ERI','ESH','ESP','EST','ETH','FIN','FJI','FLK','FRA','FRO','FSM','GAB','GBR','GEO','GGY','GHA','GIB','GIN','GLP','GMB','GNB','GNQ','GRC','GRD','GRL','GTM','GUF','GUM','GUY','HKG','HMD','HND','HRV','HTI','HUN','IDN','IMN','IND','IOT','IRL','IRN','IRQ','ISL','ISR','ITA','JAM','JEY','JOR','JPN','KAZ','KEN','KGZ','KHM','KIR','KNA','KOR','KWT','LAO','LBN','LBR','LBY','LCA','LIE','LKA','LSO','LTU','LUX','LVA','MAC','MAF','MAR','MCO','MDA','MDG','MDV','MEX','MHL','MKD','MLI','MLT','MMR','MNE','MNG','MNP','MOZ','MRT','MSR','MTQ','MUS','MWI','MYS','MYT','NAM','NCL','NER','NFK','NGA','NIC','NIU','NLD','NOR','NPL','NRU','NZL','OMN','PAK','PAN','PCN','PER','PHL','PLW','PNG','POL','PRI','PRK','PRT','PRY','PSE','PYF','QAT','REU','ROU','RUS','RWA','SAU','SDN','SEN','SGP','SGS','SHN','SJM','SLB','SLE','SLV','SMR','SOM','SPM','SRB','SSD','STP','SUR','SVK','SVN','SWE','SWZ','SXM','SYC','SYR','TCA','TCD','TGO','THA','TJK','TKL','TKM','TLS','TON','TTO','TUN','TUR','TUV','TWN','TZA','UGA','UKR','UMI','URY','USA','UZB','VAT','VCT','VEN','VGB','VIR','VNM','VUT','WLF','WSM','YEM','ZAF','ZMB','ZWE'])) then return false; end if;
    sum := public.luhn36(m[2] || m[4]);
    return sum is null or sum = m[3];                                    -- null: cannot judge
  end if;
  m := regexp_match(id, '^([A-Z]{3})([0-9a-z]{12})([0-9a-z])$');
  if m is not null then return m[1] = any (array['ABW','AFG','AGO','AIA','ALA','ALB','AND','ARE','ARG','ARM','ASM','ATA','ATF','ATG','AUS','AUT','AZE','BDI','BEL','BEN','BES','BFA','BGD','BGR','BHR','BHS','BIH','BLM','BLR','BLZ','BMU','BOL','BRA','BRB','BRN','BTN','BVT','BWA','CAF','CAN','CCK','CHE','CHL','CHN','CIV','CMR','COD','COG','COK','COL','COM','CPV','CRI','CUB','CUW','CXR','CYM','CYP','CZE','DEU','DJI','DMA','DNK','DOM','DZA','ECU','EGY','ERI','ESH','ESP','EST','ETH','FIN','FJI','FLK','FRA','FRO','FSM','GAB','GBR','GEO','GGY','GHA','GIB','GIN','GLP','GMB','GNB','GNQ','GRC','GRD','GRL','GTM','GUF','GUM','GUY','HKG','HMD','HND','HRV','HTI','HUN','IDN','IMN','IND','IOT','IRL','IRN','IRQ','ISL','ISR','ITA','JAM','JEY','JOR','JPN','KAZ','KEN','KGZ','KHM','KIR','KNA','KOR','KWT','LAO','LBN','LBR','LBY','LCA','LIE','LKA','LSO','LTU','LUX','LVA','MAC','MAF','MAR','MCO','MDA','MDG','MDV','MEX','MHL','MKD','MLI','MLT','MMR','MNE','MNG','MNP','MOZ','MRT','MSR','MTQ','MUS','MWI','MYS','MYT','NAM','NCL','NER','NFK','NGA','NIC','NIU','NLD','NOR','NPL','NRU','NZL','OMN','PAK','PAN','PCN','PER','PHL','PLW','PNG','POL','PRI','PRK','PRT','PRY','PSE','PYF','QAT','REU','ROU','RUS','RWA','SAU','SDN','SEN','SGP','SGS','SHN','SJM','SLB','SLE','SLV','SMR','SOM','SPM','SRB','SSD','STP','SUR','SVK','SVN','SWE','SWZ','SXM','SYC','SYR','TCA','TCD','TGO','THA','TJK','TKL','TKM','TLS','TON','TTO','TUN','TUR','TUV','TWN','TZA','UGA','UKR','UMI','URY','USA','UZB','VAT','VCT','VEN','VGB','VIR','VNM','VUT','WLF','WSM','YEM','ZAF','ZMB','ZWE']); end if;
  if length(id) between 10 and 24 and upper(left(id, 3)) = any (array['AUT','BEL','BGR','HRV','CYP','CZE','DNK','EST','FIN','FRA','DEU','GRC','HUN','IRL','ITA','LVA','LTU','LUX','MLT','NLD','POL','PRT','ROU','SVK','SVN','ESP','SWE','NOR','ISL','LIE','CHE','GBR']) then
    return false;                                                        -- EN 4709 country, broken format
  end if;
  return true;                                                           -- other national formats
end $$;

do $$
declare src text; n int := 0;
begin
  select pg_get_functiondef('public.spoof_check(public.detections)'::regprocedure) into src;
  if position('seen_at' in src) = 0 then
    src := replace(src, 'select det.created_at, det.drone_lat, det.drone_lon into prev',
                        'select det.created_at, det.seen_at, det.drone_lat, det.drone_lon into prev');
    src := replace(src, 'secs := greatest(extract(epoch from (now() - prev.created_at)), 1);',
                        'secs := abs(extract(epoch from (coalesce(d.seen_at, now()) - coalesce(prev.seen_at, prev.created_at))));');
    src := replace(src, 'elsif dist > 50 and dist / secs > 70 then',
                        'elsif dist > 70 * secs + 50 then');
    if position('70 * secs + 50' in src) = 0 or position('prev.seen_at' in src) = 0 then
      raise exception 'spoof_check did not match the expected text — not changed';
    end if;
    execute src;
  end if;
end $$;

revoke execute on function public.luhn36(text) from public, anon;
grant execute on function public.luhn36(text), public.operator_id_ok(text) to authenticated, service_role;
