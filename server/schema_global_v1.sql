-- =====================================================================
--  DroneRadar — global identity checks (run once, after schema_security_v1.sql)
--  Operator IDs in the EU / UK / EASA format: ISO 3166 alpha-3 country + 12 base-36 chars
--  + 1 checksum char (EN 4709-002). A number from an EASA/UK country that breaks the format,
--  or one in that format naming a country that does not exist, gets the weak flag 'bad_operator_id'.
-- =====================================================================

create or replace function public.operator_id_ok(p text)
returns boolean
language sql immutable as $$
  select case
    when p is null or btrim(p) = '' then true
    when upper(btrim(p)) ~ '^FA[0-9A-Z]{8}$' then true                                  -- FAA registration
    when btrim(p) ~* '^[A-Z]{3}[0-9a-z]{13}(-[0-9a-z]{3})?$'
      then upper(left(btrim(p), 3)) = any (array['ABW','AFG','AGO','AIA','ALA','ALB','AND','ARE','ARG','ARM','ASM','ATA','ATF','ATG','AUS','AUT','AZE','BDI','BEL','BEN','BES','BFA','BGD','BGR','BHR','BHS','BIH','BLM','BLR','BLZ','BMU','BOL','BRA','BRB','BRN','BTN','BVT','BWA','CAF','CAN','CCK','CHE','CHL','CHN','CIV','CMR','COD','COG','COK','COL','COM','CPV','CRI','CUB','CUW','CXR','CYM','CYP','CZE','DEU','DJI','DMA','DNK','DOM','DZA','ECU','EGY','ERI','ESH','ESP','EST','ETH','FIN','FJI','FLK','FRA','FRO','FSM','GAB','GBR','GEO','GGY','GHA','GIB','GIN','GLP','GMB','GNB','GNQ','GRC','GRD','GRL','GTM','GUF','GUM','GUY','HKG','HMD','HND','HRV','HTI','HUN','IDN','IMN','IND','IOT','IRL','IRN','IRQ','ISL','ISR','ITA','JAM','JEY','JOR','JPN','KAZ','KEN','KGZ','KHM','KIR','KNA','KOR','KWT','LAO','LBN','LBR','LBY','LCA','LIE','LKA','LSO','LTU','LUX','LVA','MAC','MAF','MAR','MCO','MDA','MDG','MDV','MEX','MHL','MKD','MLI','MLT','MMR','MNE','MNG','MNP','MOZ','MRT','MSR','MTQ','MUS','MWI','MYS','MYT','NAM','NCL','NER','NFK','NGA','NIC','NIU','NLD','NOR','NPL','NRU','NZL','OMN','PAK','PAN','PCN','PER','PHL','PLW','PNG','POL','PRI','PRK','PRT','PRY','PSE','PYF','QAT','REU','ROU','RUS','RWA','SAU','SDN','SEN','SGP','SGS','SHN','SJM','SLB','SLE','SLV','SMR','SOM','SPM','SRB','SSD','STP','SUR','SVK','SVN','SWE','SWZ','SXM','SYC','SYR','TCA','TCD','TGO','THA','TJK','TKL','TKM','TLS','TON','TTO','TUN','TUR','TUV','TWN','TZA','UGA','UKR','UMI','URY','USA','UZB','VAT','VCT','VEN','VGB','VIR','VNM','VUT','WLF','WSM','YEM','ZAF','ZMB','ZWE'])
    when length(btrim(p)) between 10 and 24 and upper(left(btrim(p), 3)) = any (array['AUT','BEL','BGR','HRV','CYP','CZE','DNK','EST','FIN','FRA','DEU','GRC','HUN','IRL','ITA','LVA','LTU','LUX','MLT','NLD','POL','PRT','ROU','SVK','SVN','ESP','SWE','NOR','ISL','LIE','CHE','GBR'])
      then false                                                                         -- EU-style but malformed
    else true                                                                            -- other national formats
  end
$$;

-- add the check to spoof_check (keeps everything else as it is)
do $$
declare src text;
begin
  select pg_get_functiondef('public.spoof_check(public.detections)'::regprocedure) into src;
  if position('bad_operator_id' in src) = 0 then
    src := replace(src, '  -- 6. heard for',
      E'  if d.operator_id is not null and not public.operator_id_ok(d.operator_id) then\n'
      || E'    flags := array_append(flags, ''bad_operator_id'');\n  end if;\n\n  -- 6. heard for');
    execute src;
  end if;
end $$;
revoke execute on function public.operator_id_ok(text) from public, anon;
grant execute on function public.operator_id_ok(text) to authenticated, service_role;
