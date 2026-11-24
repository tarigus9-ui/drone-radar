-- =====================================================================
--  DroneRadar — Phase 3: incident history, flight tracks, live coverage
--  Run once in Supabase → SQL Editor (after schema.sql and schema_phase2.sql).
-- =====================================================================

create index if not exists detections_key_time on public.detections (company_id, coalesce(serial, drone_key), created_at);

-- An "incident" = one drone (by serial, else radio id) seen continuously; a gap > 10 min starts a new one.
-- security invoker → the RLS rules decide what each person sees (guard: own, site/region manager: theirs, admin: all).
create or replace function public.incidents(p_since timestamptz, p_until timestamptz default now())
returns table (drone text, serial text, started timestamptz, ended timestamptz, points integer,
               reporters text, site text, region text, max_height real,
               last_lat double precision, last_lon double precision,
               op_lat double precision, op_lon double precision, operator_id text)
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
  select s.k,
         max(s.serial),
         min(s.created_at),
         max(s.created_at),
         count(*)::int,
         string_agg(distinct coalesce(p.full_name, 'حارس'), '، '),
         (array_agg(st.name order by s.created_at desc) filter (where st.name is not null))[1],
         (array_agg(r.name  order by s.created_at desc) filter (where r.name  is not null))[1],
         max(s.drone_height),
         (array_agg(s.drone_lat order by s.created_at desc) filter (where s.drone_lat is not null))[1],
         (array_agg(s.drone_lon order by s.created_at desc) filter (where s.drone_lon is not null))[1],
         (array_agg(s.op_lat    order by s.created_at desc) filter (where s.op_lat    is not null))[1],
         (array_agg(s.op_lon    order by s.created_at desc) filter (where s.op_lon    is not null))[1],
         (array_agg(s.operator_id order by s.created_at desc) filter (where s.operator_id is not null))[1]
  from s
  left join public.profiles p  on p.user_id = s.reported_by
  left join public.sites    st on st.id = s.site_id
  left join public.regions  r  on r.id = s.region_id
  group by s.k, s.sess
  order by max(s.created_at) desc
  limit 300
$$;

-- Every point of one incident, for drawing the flight path and the operator's movement.
create or replace function public.incident_track(p_drone text, p_from timestamptz, p_to timestamptz)
returns table (t timestamptz, lat double precision, lon double precision, height real, speed real,
               op_lat double precision, op_lon double precision,
               reporter_lat double precision, reporter_lon double precision)
language sql stable security invoker set search_path = public as $$
  select created_at, drone_lat, drone_lon, drone_height, speed, op_lat, op_lon, reporter_lat, reporter_lon
  from public.detections
  where coalesce(serial, drone_key) = p_drone
    and created_at between p_from - interval '1 second' and p_to + interval '1 second'
  order by created_at
  limit 5000
$$;

-- Live coverage: where each staff phone / station is, battery, and whether it is still reporting.
create or replace function public.coverage()
returns table (device_id text, kind text, name text, role public.user_role, site text,
               lat double precision, lon double precision, battery smallint, scanning boolean,
               last_seen timestamptz, online boolean)
language sql stable security definer set search_path = public as $$
  with me as (select * from public.profiles where user_id = auth.uid())
  select d.device_id, d.kind, coalesce(p.full_name, d.model), p.role, s.name,
         d.lat, d.lon, d.battery, d.scanning, d.last_seen,
         d.last_seen > now() - interval '3 minutes'
  from public.devices d
  join me on me.company_id = d.company_id
  left join public.profiles p on p.user_id = d.user_id
  left join public.sites    s on s.id = p.site_id
  where me.role = 'admin'
     or (me.role = 'region_manager' and (p.region_id = me.region_id or s.region_id = me.region_id))
     or (me.role = 'site_manager'   and p.site_id = me.site_id)
  order by d.last_seen desc
$$;

grant execute on function public.incidents(timestamptz, timestamptz),
  public.incident_track(text, timestamptz, timestamptz),
  public.coverage() to authenticated;
