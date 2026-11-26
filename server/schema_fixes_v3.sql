-- =====================================================================
--  DroneRadar — review fixes v3 (run once, after schema_fixes_v2.sql)
--  Finding 11: a location message that arrives without a Basic ID used to become its own
--  "drone" named after the sender's radio address (MAC). Now:
--  * the record keeps drone_key (the sender address) as its key;
--  * as soon as the same sender broadcasts a serial (before or after, within 10 minutes),
--    the serial is filled in and serial_inferred = true records that it was not in that message;
--  * the report never shows a radio address as a serial and never counts it as a distinct drone
--    (it is counted separately as "unidentified").
--  Finding 12: the report's incident list carries the last numeric positions (drone, operator).
--  Nothing is deleted. Only rows with serial IS NULL are completed, and they are marked.
-- =====================================================================

alter table public.detections add column if not exists serial_inferred boolean not null default false;

create index if not exists detections_key_recent on public.detections (company_id, drone_key, created_at desc);

-- 1. Before insert (runs after detections_bi, before detections_bj = spoof check):
--    a row without serial takes the serial its sender broadcast in the last 10 minutes.
create or replace function public.detections_resolve_serial()
returns trigger
language plpgsql security definer set search_path = public as $$
declare s text;
begin
  if new.serial is null and new.drone_key is not null then
    select det.serial into s
      from public.detections det
     where det.company_id = new.company_id
       and det.drone_key  = new.drone_key
       and det.serial is not null
       and det.created_at > now() - interval '10 minutes'
     order by det.created_at desc limit 1;
    if s is not null then
      new.serial := s;
      new.serial_inferred := true;
      new.whitelisted := public.is_whitelisted(new.company_id, new.serial, new.site_id, new.operator_id);
    end if;
  end if;
  return new;
end $$;

drop trigger if exists detections_bif on public.detections;
create trigger detections_bif before insert on public.detections
  for each row execute function public.detections_resolve_serial();

-- 2. After insert: when a serial arrives, complete the sender's earlier serial-less rows.
create or replace function public.detections_backfill_serial()
returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.serial is not null and not new.serial_inferred and new.drone_key is not null then
    update public.detections det
       set serial = new.serial,
           serial_inferred = true,
           whitelisted = public.is_whitelisted(det.company_id, new.serial, det.site_id, det.operator_id)
     where det.company_id = new.company_id
       and det.drone_key  = new.drone_key
       and det.serial is null
       and det.created_at > new.created_at - interval '10 minutes';
  end if;
  return null;
end $$;

drop trigger if exists detections_ai_serial on public.detections;
create trigger detections_ai_serial after insert on public.detections
  for each row execute function public.detections_backfill_serial();

revoke execute on function public.detections_resolve_serial()  from public, anon, authenticated;
revoke execute on function public.detections_backfill_serial() from public, anon, authenticated;

-- 3. One-time completion of rows already stored (same rule: same sender, within 10 minutes).
with m as (
  select det.id,
         (select o.serial from public.detections o
           where o.company_id = det.company_id and o.drone_key = det.drone_key
             and o.serial is not null and not o.serial_inferred
             and o.created_at between det.created_at - interval '10 minutes' and det.created_at + interval '10 minutes'
           order by abs(extract(epoch from (o.created_at - det.created_at))) limit 1) as s
    from public.detections det
   where det.serial is null and det.drone_key is not null
)
update public.detections det
   set serial = m.s, serial_inferred = true,
       whitelisted = public.is_whitelisted(det.company_id, m.s, det.site_id, det.operator_id)
  from m
 where det.id = m.id and m.s is not null;

-- 4. Report: radio address is never a serial nor a distinct drone; incidents carry numeric positions.
--    Full definition (no text patching, so copy/paste whitespace cannot break it).
CREATE OR REPLACE FUNCTION public.report_summary(p_from timestamp with time zone, p_to timestamp with time zone, p_tz text DEFAULT 'Asia/Dubai'::text)
 RETURNS json
 LANGUAGE sql
 STABLE
 SET search_path TO 'public'
AS $function$
  with d as (
    select det.*, coalesce(det.serial, det.drone_key) as k
    from public.detections det
    where det.created_at between p_from and p_to
  ), g as (
    select d.*,
           case when lag(created_at) over (partition by k order by created_at) is null
                  or created_at - lag(created_at) over (partition by k order by created_at) > interval '10 minutes'
                then 1 else 0 end as brk
    from d
  ), s as (
    select g.*, sum(brk) over (partition by k order by created_at) as sess from g
  ), inc as (
    select k, sess,
           max(serial) as serial,
           min(created_at) as started, max(created_at) as ended, count(*) as pts,
           (array_agg(site_id order by created_at desc) filter (where site_id is not null))[1] as site_id,
           bool_and(whitelisted) as authorized,
           bool_or(cardinality(spoof_flags) > 0) as spoof,
           bool_or(op_lat is not null) as op_known,
           max(drone_height) as max_h,
           (array_agg(drone_lat order by created_at desc) filter (where drone_lat is not null))[1] as last_lat,
           (array_agg(drone_lon order by created_at desc) filter (where drone_lon is not null))[1] as last_lon,
           (array_agg(op_lat order by created_at desc) filter (where op_lat is not null))[1] as last_op_lat,
           (array_agg(op_lon order by created_at desc) filter (where op_lon is not null))[1] as last_op_lon,
           count(distinct reported_by) as reporters
    from s group by k, sess
  ), sg as (
    select * from public.sightings where created_at between p_from and p_to
  )
  select json_build_object(
    'company',      (select name from public.companies where id = public.my_company()),
    'role',         (public.my_profile()).role,
    'from',         p_from,
    'to',           p_to,
    'incidents',    (select count(*) from inc),
    'drones',       (select count(distinct k) from inc where serial is not null),
    'unidentified', (select count(distinct k) from inc where serial is null),
    'authorized',   (select count(*) from inc where authorized),
    'suspicious',   (select count(*) from inc where spoof),
    'op_known',     (select count(*) from inc where op_known),
    'detections',   (select count(*) from d),
    'sightings',    (select count(*) from sg),
    'triangulated', (select count(*) from sg where est_lat is not null),
    'by_day', (select coalesce(json_agg(json_build_object('day', day, 'n', n) order by day), '[]'::json)
               from (select (started at time zone p_tz)::date as day, count(*) as n from inc group by 1) x),
    'by_hour', (select coalesce(json_agg(json_build_object('h', h, 'n', n) order by h), '[]'::json)
                from (select extract(hour from started at time zone p_tz)::int as h, count(*) as n from inc group by 1) x),
    'by_site', (select coalesce(json_agg(json_build_object('site', site, 'n', n, 'sus', sus, 'auth', auth) order by n desc), '[]'::json)
                from (select coalesce(st.name, '—') as site, count(*) as n,
                             count(*) filter (where spoof) as sus, count(*) filter (where authorized) as auth
                      from inc left join public.sites st on st.id = inc.site_id group by 1) x),
    'top_drones', (select coalesce(json_agg(json_build_object('serial', serial, 'n', n, 'last', last, 'sus', sus, 'auth', auth) order by n desc, last desc), '[]'::json)
                   from (select max(serial) as serial, count(*) as n, max(ended) as last,
                                bool_or(spoof) as sus, bool_and(authorized) as auth
                         from inc where serial is not null group by k order by 2 desc, 3 desc limit 10) x),
    'flags', (select coalesce(json_agg(json_build_object('flag', f, 'n', n) order by n desc), '[]'::json)
              from (select f, count(distinct (k, sess)) as n from s, unnest(spoof_flags) f group by f) x),
    'staff', (select coalesce(json_agg(json_build_object('name', name, 'det', det, 'sight', sight) order by det + sight desc), '[]'::json)
              from (select coalesce(p.full_name, 'حارس') as name,
                           (select count(*) from d where d.reported_by = p.user_id) as det,
                           (select count(*) from sg where sg.reported_by = p.user_id) as sight
                    from public.profiles p
                    where p.user_id in (select reported_by from d union select reported_by from sg)) x),
    'list', (select coalesce(json_agg(json_build_object(
                       'serial', serial, 'key', k, 'started', started,
                       'lat', last_lat, 'lon', last_lon, 'op_lat', last_op_lat, 'op_lon', last_op_lon, 'ended', ended, 'site', site,
                       'auth', authorized, 'sus', spoof, 'op', op_known, 'max_h', max_h) order by started desc), '[]'::json)
             from (select inc.*, st.name as site from inc left join public.sites st on st.id = inc.site_id
                   order by started desc limit 200) x)
  )
$function$;

grant execute on function public.report_summary(timestamptz, timestamptz, text) to authenticated;
