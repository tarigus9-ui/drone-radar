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
do $$
declare src text;
begin
  select pg_get_functiondef('public.report_summary(timestamptz, timestamptz, text)'::regprocedure) into src;
  if position('unidentified' in src) = 0 then
    src := replace(src, $r$'drones',       (select count(distinct k) from inc),$r$,
                        $r$'drones',       (select count(distinct k) from inc where serial is not null),
    'unidentified', (select count(distinct k) from inc where serial is null),$r$);
    src := replace(src, $r$max(drone_height) as max_h,$r$,
                        $r$max(drone_height) as max_h,
           (array_agg(drone_lat order by created_at desc) filter (where drone_lat is not null))[1] as last_lat,
           (array_agg(drone_lon order by created_at desc) filter (where drone_lon is not null))[1] as last_lon,
           (array_agg(op_lat order by created_at desc) filter (where op_lat is not null))[1] as last_op_lat,
           (array_agg(op_lon order by created_at desc) filter (where op_lon is not null))[1] as last_op_lon,$r$);
    src := replace(src, $r$(select coalesce(max(serial), k) as serial, count(*) as n, max(ended) as last,
                                bool_or(spoof) as sus, bool_and(authorized) as auth
                         from inc group by k$r$,
                        $r$(select max(serial) as serial, count(*) as n, max(ended) as last,
                                bool_or(spoof) as sus, bool_and(authorized) as auth
                         from inc where serial is not null group by k$r$);
    src := replace(src, $r$'serial', coalesce(serial, k), 'started', started,$r$,
                        $r$'serial', serial, 'key', k, 'started', started,
                       'lat', last_lat, 'lon', last_lon, 'op_lat', last_op_lat, 'op_lon', last_op_lon,$r$);
    if position('unidentified' in src) = 0 or position('last_op_lon' in src) = 0
       or position('where serial is not null group by k' in src) = 0 or position($r$'key', k$r$ in src) = 0 then
      raise exception 'report_summary: unexpected definition, patch not applied';
    end if;
    execute src;
  end if;
end $$;
