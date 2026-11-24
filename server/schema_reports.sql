-- =====================================================================
--  DroneRadar — Report data (monthly / any period) for the PDF report
--  Run once in Supabase → SQL Editor (after all earlier schema files).
--  security invoker → each manager's report covers only what he may see.
-- =====================================================================

create or replace function public.report_summary(p_from timestamptz, p_to timestamptz, p_tz text default 'Asia/Dubai')
returns json
language sql stable security invoker set search_path = public as $$
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
    'drones',       (select count(distinct k) from inc),
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
                   from (select coalesce(max(serial), k) as serial, count(*) as n, max(ended) as last,
                                bool_or(spoof) as sus, bool_and(authorized) as auth
                         from inc group by k order by 2 desc, 3 desc limit 10) x),
    'flags', (select coalesce(json_agg(json_build_object('flag', f, 'n', n) order by n desc), '[]'::json)
              from (select f, count(distinct (k, sess)) as n from s, unnest(spoof_flags) f group by f) x),
    'staff', (select coalesce(json_agg(json_build_object('name', name, 'det', det, 'sight', sight) order by det + sight desc), '[]'::json)
              from (select coalesce(p.full_name, 'حارس') as name,
                           (select count(*) from d where d.reported_by = p.user_id) as det,
                           (select count(*) from sg where sg.reported_by = p.user_id) as sight
                    from public.profiles p
                    where p.user_id in (select reported_by from d union select reported_by from sg)) x),
    'list', (select coalesce(json_agg(json_build_object(
                       'serial', coalesce(serial, k), 'started', started, 'ended', ended, 'site', site,
                       'auth', authorized, 'sus', spoof, 'op', op_known, 'max_h', max_h) order by started desc), '[]'::json)
             from (select inc.*, st.name as site from inc left join public.sites st on st.id = inc.site_id
                   order by started desc limit 200) x)
  )
$$;

grant execute on function public.report_summary(timestamptz, timestamptz, text) to authenticated;
