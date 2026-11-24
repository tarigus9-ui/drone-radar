-- =====================================================================
--  DroneRadar platform — Supabase schema (Phase 1)
--  Paste the whole file into Supabase → SQL Editor → Run.
--  Safe to run once on a fresh project.
-- =====================================================================

create extension if not exists postgis;

-- ---------- Organisation hierarchy: company → region/country → site ----------
create table public.companies (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  created_at  timestamptz not null default now()
);

create table public.regions (
  id            uuid primary key default gen_random_uuid(),
  company_id    uuid not null references public.companies on delete cascade,
  name          text not null,                 -- e.g. "الإمارات" / "UAE - Dubai"
  country_code  text,                          -- ISO: AE, SA, QA ...
  timezone      text not null default 'Asia/Dubai',
  created_at    timestamptz not null default now()
);

create table public.sites (
  id              uuid primary key default gen_random_uuid(),
  company_id      uuid not null references public.companies on delete cascade,
  region_id       uuid references public.regions on delete set null,
  name            text not null,
  boundary        geography(Polygon, 4326),    -- optional exact perimeter
  center          geography(Point, 4326),      -- or a centre point + radius
  alert_radius_m  integer not null default 500,
  created_at      timestamptz not null default now()
);
create index on public.sites using gist (boundary);
create index on public.sites using gist (center);

create type public.user_role as enum ('guard', 'site_manager', 'region_manager', 'admin');

create table public.profiles (
  user_id     uuid primary key references auth.users on delete cascade,
  company_id  uuid not null references public.companies on delete cascade,
  full_name   text,
  role        public.user_role not null default 'guard',
  region_id   uuid references public.regions on delete set null,
  site_id     uuid references public.sites on delete set null,
  created_at  timestamptz not null default now()
);

-- ---------- Detections sent by phones (and later ESP32 stations) ----------
create table public.detections (
  id             bigint generated always as identity primary key,
  company_id     uuid references public.companies on delete cascade,
  reported_by    uuid references auth.users on delete set null,
  device_id      text,
  created_at     timestamptz not null default now(),
  seen_at        timestamptz,                  -- phone clock
  source         text,                         -- Bluetooth / Wi-Fi Beacon / Wi-Fi NaN
  drone_key      text,                         -- MAC / peer id
  serial         text,
  operator_id    text,
  description    text,
  ua_type        smallint,
  drone_lat      double precision,
  drone_lon      double precision,
  drone_alt      real,
  drone_height   real,
  speed          real,
  heading        real,
  op_lat         double precision,
  op_lon         double precision,
  rssi           smallint,
  reporter_lat   double precision,
  reporter_lon   double precision,
  reporter_acc   real,
  site_id        uuid references public.sites on delete set null,
  region_id      uuid references public.regions on delete set null
);
create index on public.detections (company_id, created_at desc);
create index on public.detections (serial);
create index on public.detections (site_id, created_at desc);

-- ---------- Phone / station heartbeat (for the live coverage map) ----------
create table public.devices (
  device_id     text primary key,
  company_id    uuid references public.companies on delete cascade,
  user_id       uuid references auth.users on delete set null,
  kind          text not null default 'phone',  -- phone / station
  model         text,
  battery       smallint,
  scanning      boolean,
  lat           double precision,
  lon           double precision,
  last_seen     timestamptz not null default now()
);

-- ---------- Helpers ----------
create or replace function public.my_profile()
returns public.profiles
language sql stable security definer set search_path = public as $$
  select * from public.profiles where user_id = auth.uid()
$$;

create or replace function public.my_company()
returns uuid
language sql stable security definer set search_path = public as $$
  select company_id from public.profiles where user_id = auth.uid()
$$;

-- Fill company / reporter / site / region on the server — never trust the phone for these.
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

  -- Locate by the drone position, else by the guard position.
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
  return new;
end $$;

create trigger detections_bi before insert on public.detections
  for each row execute function public.detections_before_insert();

create or replace function public.devices_before_write()
returns trigger
language plpgsql security definer set search_path = public as $$
begin
  new.company_id := public.my_company();
  new.user_id    := auth.uid();
  new.last_seen  := now();
  return new;
end $$;

create trigger devices_biu before insert or update on public.devices
  for each row execute function public.devices_before_write();

-- ---------- Row Level Security ----------
alter table public.companies  enable row level security;
alter table public.regions    enable row level security;
alter table public.sites      enable row level security;
alter table public.profiles   enable row level security;
alter table public.detections enable row level security;
alter table public.devices    enable row level security;

create policy "own company" on public.companies for select
  using (id = public.my_company());

create policy "company regions" on public.regions for select
  using (company_id = public.my_company());
create policy "admin manages regions" on public.regions for all
  using (company_id = public.my_company() and (public.my_profile()).role = 'admin')
  with check (company_id = public.my_company() and (public.my_profile()).role = 'admin');

create policy "company sites" on public.sites for select
  using (company_id = public.my_company());
create policy "admin manages sites" on public.sites for all
  using (company_id = public.my_company() and (public.my_profile()).role = 'admin')
  with check (company_id = public.my_company() and (public.my_profile()).role = 'admin');

create policy "company profiles" on public.profiles for select
  using (company_id = public.my_company());

-- Anyone with a profile may report; the trigger stamps company & reporter.
create policy "report detections" on public.detections for insert
  with check (auth.uid() is not null);

-- Who sees what: admin → whole company; region manager → his region;
-- site manager → his site; guard → his own reports.
create policy "see detections by role" on public.detections for select
  using (
    company_id = public.my_company() and (
         (public.my_profile()).role = 'admin'
      or ((public.my_profile()).role = 'region_manager' and region_id = (public.my_profile()).region_id)
      or ((public.my_profile()).role = 'site_manager'   and site_id   = (public.my_profile()).site_id)
      or reported_by = auth.uid()
    )
  );

create policy "own device insert" on public.devices for insert
  with check (auth.uid() is not null);
create policy "own device update" on public.devices for update
  using (user_id = auth.uid() or company_id = public.my_company());
create policy "company devices" on public.devices for select
  using (company_id = public.my_company());

-- ---------- Realtime (live map for managers — next phase) ----------
alter publication supabase_realtime add table public.detections;
alter publication supabase_realtime add table public.devices;
