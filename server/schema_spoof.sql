-- =====================================================================
--  DroneRadar — Spoofed / fake Remote ID detection
--  Run once in Supabase → SQL Editor (after all earlier schema files).
--
--  Each detection gets spoof_flags (empty = looks genuine):
--    impossible_speed   jumped faster than any drone can fly, or reports > 250 km/h
--    impossible_alt     height outside -100 … 1500 m
--    serial_clone       same serial seen at two places > 2 km apart within 10 s
--    signal_mismatch    very strong signal but the drone claims to be far from the guard
--    operator_far       operator claims to be > 15 km from the drone
--    bad_serial         serial does not follow ANSI/CTA-2063 (forged, or a non-compliant maker) — weak sign
--    not_heard_nearby   another guard 150 m away, online for a minute, never heard it
--    whitelist_spoof    an AUTHORIZED serial with any strong flag → likely impersonation
-- =====================================================================

alter table public.detections add column if not exists id_type smallint;
alter table public.detections add column if not exists spoof_flags text[] not null default '{}';

-- ANSI/CTA-2063-A: 4-char manufacturer code + 1 hex length code + that many chars (no O / I).
create or replace function public.cta2063_ok(s text)
returns boolean
language sql immutable as $$
  select s is not null
     and upper(s) ~ '^[0-9A-HJ-NP-Z]{4}[1-9A-F][0-9A-HJ-NP-Z]{1,15}$'
     and length(s) - 5 = position(upper(substr(s, 5, 1)) in '0123456789ABCDEF') - 1
$$;

create or replace function public.spoof_check(d public.detections)
returns text[]
language plpgsql stable security definer set search_path = public as $$
declare
  flags text[] := '{}';
  pt  geography; rp geography; op geography;
  prev record; dist double precision; secs double precision;
  d_guard double precision;
  strong text[] := array['impossible_speed', 'serial_clone', 'signal_mismatch', 'impossible_alt'];
  first_seen timestamptz;
begin
  if d.drone_lat is not null and d.drone_lon is not null then
    pt := st_setsrid(st_makepoint(d.drone_lon, d.drone_lat), 4326)::geography;
  end if;
  if d.reporter_lat is not null and d.reporter_lon is not null then
    rp := st_setsrid(st_makepoint(d.reporter_lon, d.reporter_lat), 4326)::geography;
  end if;

  -- 1. reported values that no drone can have
  if d.speed is not null and d.speed > 70 then flags := array_append(flags, 'impossible_speed'); end if;
  if d.drone_height is not null and (d.drone_height > 1500 or d.drone_height < -100) then flags := array_append(flags, 'impossible_alt'); end if;

  -- 2. jumps / clones: compare with the previous position of the same drone (any guard, last 2 min)
  if pt is not null then
    select det.created_at, det.drone_lat, det.drone_lon into prev
    from public.detections det
    where det.company_id = d.company_id
      and coalesce(det.serial, det.drone_key) = coalesce(d.serial, d.drone_key)
      and det.drone_lat is not null
      and det.created_at > now() - interval '2 minutes'
    order by det.created_at desc limit 1;
    if prev.created_at is not null then
      dist := st_distance(pt, st_setsrid(st_makepoint(prev.drone_lon, prev.drone_lat), 4326)::geography);
      secs := greatest(extract(epoch from (now() - prev.created_at)), 1);
      if dist > 2000 and secs <= 10 and d.serial is not null then flags := array_append(flags, 'serial_clone');
      elsif dist > 50 and dist / secs > 70 then flags := array_append(flags, 'impossible_speed');
      end if;
    end if;
  end if;

  -- 3. strong radio signal but claims to be far away from this guard
  if pt is not null and rp is not null and d.rssi is not null and d.rssi < 0 then
    d_guard := st_distance(pt, rp);
    if (d.source ilike '%bluetooth%' and d.rssi > -60 and d_guard > 800)
       or (d.source ilike '%wi-fi%' and d.rssi > -50 and d_guard > 1500) then
      flags := array_append(flags, 'signal_mismatch');
    end if;
  end if;

  -- 4. operator impossibly far from his drone
  if pt is not null and d.op_lat is not null and d.op_lon is not null then
    op := st_setsrid(st_makepoint(d.op_lon, d.op_lat), 4326)::geography;
    if st_distance(pt, op) > 15000 then flags := array_append(flags, 'operator_far'); end if;
  end if;

  -- 5. serial number format (only when the drone says the ID is a serial number)
  if d.id_type = 1 and d.serial is not null and not public.cta2063_ok(d.serial) then
    flags := array_append(flags, 'bad_serial');
  end if;

  -- 6. heard for > 1 min by this guard, never by another online guard standing within 150 m
  if rp is not null and d.rssi is not null and d.rssi > -75 then
    select min(det.created_at) into first_seen from public.detections det
     where det.company_id = d.company_id and det.reported_by = d.reported_by
       and coalesce(det.serial, det.drone_key) = coalesce(d.serial, d.drone_key)
       and det.created_at > now() - interval '5 minutes';
    if first_seen is not null and first_seen < now() - interval '60 seconds' and exists (
        select 1 from public.devices dv
        where dv.company_id = d.company_id and dv.user_id is distinct from d.reported_by
          and dv.scanning and dv.last_seen > now() - interval '2 minutes'
          and dv.lat is not null
          and st_dwithin(st_setsrid(st_makepoint(dv.lon, dv.lat), 4326)::geography, rp, 150)
          and not exists (
            select 1 from public.detections x
            where x.company_id = d.company_id and x.reported_by = dv.user_id
              and coalesce(x.serial, x.drone_key) = coalesce(d.serial, d.drone_key)
              and x.created_at > now() - interval '2 minutes'))
    then
      flags := array_append(flags, 'not_heard_nearby');
    end if;
  end if;

  -- 7. an authorized serial that shows strong signs of forgery = impersonation
  if d.serial is not null and flags && strong
     and public.is_whitelisted(d.company_id, d.serial, d.site_id) then
    flags := array_append(flags, 'whitelist_spoof');
  end if;

  return flags;
end $$;

-- Run the checks after company/site are known (same insert, second trigger, alphabetical order: bi < bj).
create or replace function public.detections_spoof_trigger()
returns trigger
language plpgsql security definer set search_path = public as $$
begin
  new.spoof_flags := public.spoof_check(new);
  if 'whitelist_spoof' = any(new.spoof_flags) then
    new.whitelisted := false;       -- never stay silent for a forged authorized drone
  end if;
  return new;
end $$;

drop trigger if exists detections_bj on public.detections;
create trigger detections_bj before insert on public.detections
  for each row execute function public.detections_spoof_trigger();

-- Managers: alerts now carry the flags.
drop function if exists public.alerts_since(timestamptz);
create function public.alerts_since(p_since timestamptz)
returns table (id bigint, created_at timestamptz, serial text, drone_key text, source text, ua_type smallint,
               drone_lat double precision, drone_lon double precision, drone_height real, speed real, heading real,
               op_lat double precision, op_lon double precision, operator_id text,
               site text, region text, reporter text, spoof_flags text[])
language sql stable security invoker set search_path = public as $$
  select d.id, d.created_at, d.serial, d.drone_key, d.source, d.ua_type,
         d.drone_lat, d.drone_lon, d.drone_height, d.speed, d.heading,
         d.op_lat, d.op_lon, d.operator_id,
         s.name, r.name, coalesce(p.full_name, 'حارس'), d.spoof_flags
  from public.detections d
  left join public.sites    s on s.id = d.site_id
  left join public.regions  r on r.id = d.region_id
  left join public.profiles p on p.user_id = d.reported_by
  where d.created_at > p_since
    and d.reported_by is distinct from auth.uid()
    and not d.whitelisted
    and (public.my_profile()).role in ('admin', 'region_manager', 'site_manager')
  order by d.created_at
  limit 500
$$;

-- Incident list: every flag raised during the incident.
drop function if exists public.incidents(timestamptz, timestamptz);
create function public.incidents(p_since timestamptz, p_until timestamptz default now())
returns table (drone text, serial text, started timestamptz, ended timestamptz, points integer,
               reporters text, site text, region text, max_height real,
               last_lat double precision, last_lon double precision,
               op_lat double precision, op_lon double precision, operator_id text, authorized boolean,
               spoof_flags text[])
language sql stable security invoker set search_path = public as $$
  with d as (
    select det.*, coalesce(det.serial, det.drone_key) as k
    from public.detections det
    where det.created_at between p_since and p_until
  ), g as (
    select d.*,
           case when lag(created_at) over (partition by k order by created_at) is null
                  or created_at - lag(created_at) over (partition by k order by created_at) > interval '10 minutes'
                then 1 else 0 end as brk
    from d
  ), s as (
    select g.*, sum(brk) over (partition by k order by created_at) as sess from g
  )
  select s.k, max(s.serial), min(s.created_at), max(s.created_at), count(*)::int,
         string_agg(distinct coalesce(p.full_name, 'حارس'), '، '),
         (array_agg(st.name order by s.created_at desc) filter (where st.name is not null))[1],
         (array_agg(r.name  order by s.created_at desc) filter (where r.name  is not null))[1],
         max(s.drone_height),
         (array_agg(s.drone_lat order by s.created_at desc) filter (where s.drone_lat is not null))[1],
         (array_agg(s.drone_lon order by s.created_at desc) filter (where s.drone_lon is not null))[1],
         (array_agg(s.op_lat    order by s.created_at desc) filter (where s.op_lat    is not null))[1],
         (array_agg(s.op_lon    order by s.created_at desc) filter (where s.op_lon    is not null))[1],
         (array_agg(s.operator_id order by s.created_at desc) filter (where s.operator_id is not null))[1],
         bool_and(s.whitelisted),
         array(select distinct f from unnest(array_agg(array_to_string(s.spoof_flags, ','))) x, unnest(string_to_array(x, ',')) f where f <> '')
  from s
  left join public.profiles p  on p.user_id = s.reported_by
  left join public.sites    st on st.id = s.site_id
  left join public.regions  r  on r.id = s.region_id
  group by s.k, s.sess
  order by max(s.created_at) desc
  limit 300
$$;

grant execute on function public.alerts_since(timestamptz), public.incidents(timestamptz, timestamptz),
  public.cta2063_ok(text) to authenticated;
