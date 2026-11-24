-- =====================================================================
--  DroneRadar — Whitelist ("authorized drones") + "I saw a drone" sightings
--  Run once in Supabase → SQL Editor (after the earlier schema files).
-- =====================================================================

-- ============================ WHITELIST ==============================
create table if not exists public.whitelist (
  id           bigint generated always as identity primary key,
  company_id   uuid not null references public.companies on delete cascade,
  serial       text not null,
  label        text,                                        -- e.g. "درون الشركة" / "تصريح تصوير"
  site_id      uuid references public.sites on delete cascade,  -- null = all sites
  valid_until  timestamptz,                                 -- null = permanent
  created_by   uuid references auth.users on delete set null,
  created_at   timestamptz not null default now()
);
create index if not exists whitelist_company_serial on public.whitelist (company_id, upper(serial));
alter table public.whitelist enable row level security;
create policy "company whitelist" on public.whitelist for select using (company_id = public.my_company());
grant select on public.whitelist to authenticated;

alter table public.detections add column if not exists whitelisted boolean not null default false;

create or replace function public.is_whitelisted(p_company uuid, p_serial text, p_site uuid)
returns boolean
language sql stable security definer set search_path = public as $$
  select p_serial is not null and exists (
    select 1 from public.whitelist w
    where w.company_id = p_company
      and upper(w.serial) = upper(trim(p_serial))
      and (w.site_id is null or w.site_id = p_site)
      and (w.valid_until is null or w.valid_until > now()))
$$;

-- Same as before + the whitelist flag.
create or replace function public.detections_before_insert()
returns trigger
language plpgsql security definer set search_path = public as $$
declare
  p  public.profiles;
  pt geography;
  s  public.sites;
begin
  select * into p from public.profiles where user_id = auth.uid();
  if p.user_id is null then
    raise exception 'user has no profile / company';
  end if;
  new.company_id  := p.company_id;
  new.reported_by := auth.uid();
  new.created_at  := now();

  if new.drone_lat is not null and new.drone_lon is not null then
    pt := st_setsrid(st_makepoint(new.drone_lon, new.drone_lat), 4326)::geography;
  elsif new.reporter_lat is not null and new.reporter_lon is not null then
    pt := st_setsrid(st_makepoint(new.reporter_lon, new.reporter_lat), 4326)::geography;
  end if;

  if pt is not null then
    select * into s from public.sites
     where company_id = p.company_id
       and ( (boundary is not null and st_covers(boundary, pt))
          or (center   is not null and st_dwithin(center, pt, alert_radius_m)) )
     order by case when center is null then 0 else st_distance(center, pt) end
     limit 1;
    if s.id is not null then
      new.site_id   := s.id;
      new.region_id := s.region_id;
    end if;
  end if;

  if new.site_id is null then
    new.site_id   := p.site_id;
    new.region_id := coalesce(p.region_id, (select region_id from public.sites where id = p.site_id));
  end if;

  new.whitelisted := public.is_whitelisted(new.company_id, new.serial, new.site_id);
  return new;
end $$;

-- Admins and managers manage the list (site managers: only their own site).
create or replace function public.add_whitelist(p_serial text, p_label text default null,
                                                p_site_id uuid default null, p_valid_days integer default null)
returns bigint
language plpgsql security definer set search_path = public as $$
declare me public.profiles; nid bigint;
begin
  select * into me from public.profiles where user_id = auth.uid();
  if me.role not in ('admin', 'region_manager', 'site_manager') then raise exception 'managers only'; end if;
  if me.role = 'site_manager' and (p_site_id is null or p_site_id <> me.site_id) then raise exception 'site managers: own site only'; end if;
  if p_site_id is not null and not exists (select 1 from public.sites where id = p_site_id and company_id = me.company_id) then
    raise exception 'site not in your company';
  end if;
  if coalesce(trim(p_serial), '') = '' then raise exception 'serial required'; end if;
  insert into public.whitelist (company_id, serial, label, site_id, valid_until, created_by)
  values (me.company_id, upper(trim(p_serial)), nullif(trim(p_label), ''), p_site_id,
          case when p_valid_days is null or p_valid_days <= 0 then null else now() + make_interval(days => p_valid_days) end,
          auth.uid())
  returning id into nid;
  return nid;
end $$;

create or replace function public.remove_whitelist(p_id bigint)
returns void
language plpgsql security definer set search_path = public as $$
declare me public.profiles;
begin
  select * into me from public.profiles where user_id = auth.uid();
  if me.role not in ('admin', 'region_manager', 'site_manager') then raise exception 'managers only'; end if;
  delete from public.whitelist w
   where w.id = p_id and w.company_id = me.company_id
     and (me.role <> 'site_manager' or w.site_id = me.site_id);
end $$;

create or replace function public.list_whitelist()
returns table (id bigint, serial text, label text, site text, valid_until timestamptz, active boolean)
language sql stable security definer set search_path = public as $$
  select w.id, w.serial, w.label, s.name, w.valid_until, (w.valid_until is null or w.valid_until > now())
  from public.whitelist w left join public.sites s on s.id = w.site_id
  where w.company_id = public.my_company()
  order by w.created_at desc
$$;

-- Managers are no longer alerted about authorized drones.
create or replace function public.alerts_since(p_since timestamptz)
returns table (id bigint, created_at timestamptz, serial text, drone_key text, source text, ua_type smallint,
               drone_lat double precision, drone_lon double precision, drone_height real, speed real, heading real,
               op_lat double precision, op_lon double precision, operator_id text,
               site text, region text, reporter text)
language sql stable security invoker set search_path = public as $$
  select d.id, d.created_at, d.serial, d.drone_key, d.source, d.ua_type,
         d.drone_lat, d.drone_lon, d.drone_height, d.speed, d.heading,
         d.op_lat, d.op_lon, d.operator_id,
         s.name, r.name, coalesce(p.full_name, 'حارس')
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

-- Incident list now says whether the drone was authorized.
drop function if exists public.incidents(timestamptz, timestamptz);
create function public.incidents(p_since timestamptz, p_until timestamptz default now())
returns table (drone text, serial text, started timestamptz, ended timestamptz, points integer,
               reporters text, site text, region text, max_height real,
               last_lat double precision, last_lon double precision,
               op_lat double precision, op_lon double precision, operator_id text, authorized boolean)
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
         bool_or(s.whitelisted)
  from s
  left join public.profiles p  on p.user_id = s.reported_by
  left join public.sites    st on st.id = s.site_id
  left join public.regions  r  on r.id = s.region_id
  group by s.k, s.sess
  order by max(s.created_at) desc
  limit 300
$$;

-- ============================ SIGHTINGS ==============================
-- A guard points the phone at a drone that does not broadcast and presses "I saw a drone".
create table if not exists public.sightings (
  id            bigint generated always as identity primary key,
  company_id    uuid references public.companies on delete cascade,
  reported_by   uuid references auth.users on delete set null,
  created_at    timestamptz not null default now(),
  lat           double precision not null,       -- guard position
  lon           double precision not null,
  accuracy      real,
  bearing       real not null,                   -- true-north degrees the camera pointed to
  elevation     real,                            -- degrees above horizon
  note          text,
  photo_path    text,                            -- storage: sightings/<uid>/<file>.jpg
  site_id       uuid references public.sites on delete set null,
  region_id     uuid references public.regions on delete set null,
  est_lat       double precision,                -- triangulated drone position (2+ guards)
  est_lon       double precision,
  est_with      bigint                           -- the other sighting used
);
create index if not exists sightings_company_time on public.sightings (company_id, created_at desc);
alter table public.sightings enable row level security;

-- Intersection of two bearing rays (local flat-earth, good for a few km). Null if they don't meet ahead of both.
create or replace function public.bearing_fix(lat1 double precision, lon1 double precision, b1 double precision,
                                              lat2 double precision, lon2 double precision, b2 double precision)
returns double precision[]
language plpgsql immutable as $$
declare
  k  double precision := cos(radians(lat1)) * 111320.0;
  x2 double precision := (lon2 - lon1) * k;          -- metres east of point 1
  y2 double precision := (lat2 - lat1) * 111320.0;   -- metres north of point 1
  dx1 double precision := sin(radians(b1)); dy1 double precision := cos(radians(b1));
  dx2 double precision := sin(radians(b2)); dy2 double precision := cos(radians(b2));
  den double precision := dx1 * dy2 - dy1 * dx2;
  t1 double precision; t2 double precision;
begin
  if abs(den) < sin(radians(12)) then return null; end if;   -- rays too parallel → unreliable
  t1 := (x2 * dy2 - y2 * dx2) / den;
  t2 := (x2 * dy1 - y2 * dx1) / den;
  if t1 <= 0 or t2 <= 0 or t1 > 5000 or t2 > 5000 then return null; end if;
  return array[lat1 + (t1 * dy1) / 111320.0, lon1 + (t1 * dx1) / k];
end $$;

create or replace function public.sightings_before_insert()
returns trigger
language plpgsql security definer set search_path = public as $$
declare
  p  public.profiles;
  pt geography;
  s  public.sites;
  o  public.sightings;
  fx double precision[];
begin
  select * into p from public.profiles where user_id = auth.uid();
  if p.user_id is null then raise exception 'user has no profile / company'; end if;
  new.company_id := p.company_id; new.reported_by := auth.uid(); new.created_at := now();
  new.est_lat := null; new.est_lon := null; new.est_with := null;

  pt := st_setsrid(st_makepoint(new.lon, new.lat), 4326)::geography;
  select * into s from public.sites
   where company_id = p.company_id
     and ((boundary is not null and st_covers(boundary, pt))
       or (center is not null and st_dwithin(center, pt, alert_radius_m + 1500)))
   order by case when center is null then 0 else st_distance(center, pt) end limit 1;
  new.site_id := coalesce(s.id, p.site_id);
  new.region_id := coalesce(s.region_id, p.region_id);

  -- Triangulate with the latest sighting by ANOTHER guard in the last 3 minutes.
  for o in select * from public.sightings
            where company_id = p.company_id and reported_by is distinct from auth.uid()
              and created_at > now() - interval '3 minutes'
            order by created_at desc limit 5
  loop
    fx := public.bearing_fix(new.lat, new.lon, new.bearing, o.lat, o.lon, o.bearing);
    if fx is not null then
      new.est_lat := fx[1]; new.est_lon := fx[2]; new.est_with := o.id;
      update public.sightings set est_lat = fx[1], est_lon = fx[2] where id = o.id and est_lat is null;
      exit;
    end if;
  end loop;
  return new;
end $$;

drop trigger if exists sightings_bi on public.sightings;
create trigger sightings_bi before insert on public.sightings
  for each row execute function public.sightings_before_insert();

create policy "report sightings" on public.sightings for insert with check (auth.uid() is not null);
create policy "see sightings by role" on public.sightings for select
  using (
    company_id = public.my_company() and (
         (public.my_profile()).role = 'admin'
      or ((public.my_profile()).role = 'region_manager' and region_id = (public.my_profile()).region_id)
      or ((public.my_profile()).role = 'site_manager'   and site_id   = (public.my_profile()).site_id)
      or reported_by = auth.uid()
    )
  );
grant select, insert on public.sightings to authenticated;

create or replace function public.sightings_since(p_since timestamptz)
returns table (id bigint, created_at timestamptz, lat double precision, lon double precision, bearing real,
               elevation real, note text, photo_path text, est_lat double precision, est_lon double precision,
               site text, region text, reporter text, mine boolean)
language sql stable security invoker set search_path = public as $$
  select g.id, g.created_at, g.lat, g.lon, g.bearing, g.elevation, g.note, g.photo_path, g.est_lat, g.est_lon,
         s.name, r.name, coalesce(p.full_name, 'حارس'), g.reported_by = auth.uid()
  from public.sightings g
  left join public.sites    s on s.id = g.site_id
  left join public.regions  r on r.id = g.region_id
  left join public.profiles p on p.user_id = g.reported_by
  where g.created_at > p_since
  order by g.created_at desc
  limit 300
$$;

grant execute on function public.add_whitelist(text, text, uuid, integer), public.remove_whitelist(bigint),
  public.list_whitelist(), public.incidents(timestamptz, timestamptz), public.alerts_since(timestamptz),
  public.sightings_since(timestamptz) to authenticated;

alter publication supabase_realtime add table public.sightings;

-- ============================ PHOTO STORAGE ==========================
-- Private bucket; a guard uploads into his own folder; people of the same company can view.
insert into storage.buckets (id, name, public) values ('sightings', 'sightings', false)
on conflict (id) do nothing;

create policy "guard uploads own sighting photos" on storage.objects for insert to authenticated
  with check (bucket_id = 'sightings' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "company reads sighting photos" on storage.objects for select to authenticated
  using (bucket_id = 'sightings' and exists (
    select 1 from public.profiles p
    where p.user_id::text = (storage.foldername(name))[1] and p.company_id = public.my_company()));
