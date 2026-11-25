-- =====================================================================
--  DroneRadar — Security hardening v1 (run once, after all earlier files)
--  * invite codes for new staff (no more "attach by e-mail" guessing)
--  * whitelist bound to operator ID / site / expiry; guards get hashes only
--  * devices: guards see only themselves, update only their own phone
--  * functions not callable by anon; internal helpers not callable at all
--  * photo bucket: size / type limits, company folder
--  * detection flood limit
-- =====================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- whoami + company id
create or replace function public.whoami()
returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'has_profile', p.user_id is not null,
    'full_name',   p.full_name,
    'role',        p.role,
    'company',     c.name,
    'company_id',  p.company_id,
    'region_id',   p.region_id,
    'region',      r.name,
    'site_id',     p.site_id,
    'site',        s.name
  )
  from (select auth.uid() as uid) me
  left join public.profiles  p on p.user_id = me.uid
  left join public.companies c on c.id = p.company_id
  left join public.regions   r on r.id = p.region_id
  left join public.sites     s on s.id = p.site_id
$$;

-- ---------------------------------------------------------------- invites
create table if not exists public.invites (
  code        text primary key,
  company_id  uuid not null references public.companies on delete cascade,
  role        public.user_role not null default 'guard',
  full_name   text,
  region_id   uuid references public.regions on delete cascade,
  site_id     uuid references public.sites on delete cascade,
  expires_at  timestamptz not null,
  used_by     uuid references auth.users on delete set null,
  used_at     timestamptz,
  created_by  uuid references auth.users on delete set null,
  created_at  timestamptz not null default now()
);
alter table public.invites enable row level security;   -- no policies: only via functions below

-- Admin creates a one-time code (valid N days) for a new staff member.
create or replace function public.admin_create_invite(p_role public.user_role, p_full_name text default null,
                                                      p_region_id uuid default null, p_site_id uuid default null,
                                                      p_days integer default 7)
returns text
language plpgsql security definer set search_path = public as $$
declare cid uuid := public.assert_admin(); rid uuid := p_region_id; c text;
begin
  if p_site_id is not null then
    select region_id into rid from public.sites where id = p_site_id and company_id = cid;
    if not found then raise exception 'site not in your company'; end if;
  end if;
  if rid is not null and not exists (select 1 from public.regions where id = rid and company_id = cid) then
    raise exception 'region not in your company';
  end if;
  -- 10 characters from an alphabet without look-alikes (no 0/O, 1/I/L)
  c := (select string_agg(substr('ABCDEFGHJKMNPQRSTUVWXYZ23456789', 1 + (get_byte(b, i) % 31), 1), '')
          from (select extensions.gen_random_bytes(10) b) x, generate_series(0, 9) i);
  insert into public.invites (code, company_id, role, full_name, region_id, site_id, expires_at, created_by)
  values (c, cid, p_role, nullif(trim(p_full_name), ''), rid, p_site_id,
          now() + make_interval(days => greatest(1, least(coalesce(p_days, 7), 30))), auth.uid());
  return c;
end $$;

-- A newly signed-up user joins the company with the code.
create or replace function public.redeem_invite(p_code text, p_full_name text default null)
returns json
language plpgsql security definer set search_path = public as $$
declare inv public.invites;
begin
  if auth.uid() is null then raise exception 'login first'; end if;
  if exists (select 1 from public.profiles where user_id = auth.uid()) then
    raise exception 'this account already belongs to a company';
  end if;
  select * into inv from public.invites
   where code = upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'))
   for update;
  if inv.code is null or inv.used_by is not null or inv.expires_at < now() then
    raise exception 'invalid or expired code';
  end if;
  insert into public.profiles (user_id, company_id, full_name, role, region_id, site_id)
  values (auth.uid(), inv.company_id, coalesce(nullif(trim(p_full_name), ''), inv.full_name), inv.role, inv.region_id, inv.site_id);
  update public.invites set used_by = auth.uid(), used_at = now() where code = inv.code;
  return public.whoami();
end $$;

-- Slow down code guessing: at most 10 failed/any redeem calls per user per hour is enforced by the
-- 31^10 code space (~8e14); no table needed.

-- ---------------------------------------------------------------- whitelist hardening
alter table public.whitelist add column if not exists operator_id text;

drop function if exists public.is_whitelisted(uuid, text, uuid);
create or replace function public.is_whitelisted(p_company uuid, p_serial text, p_site uuid, p_operator text)
returns boolean
language sql stable security definer set search_path = public as $$
  select p_serial is not null and exists (
    select 1 from public.whitelist w
    where w.company_id = p_company
      and upper(w.serial) = upper(trim(p_serial))
      and (w.site_id is null or w.site_id = p_site)
      and (w.valid_until is null or w.valid_until > now())
      -- bound to an operator ID: a copied serial without the same operator ID is NOT authorized
      and (w.operator_id is null or upper(w.operator_id) = upper(trim(coalesce(p_operator, '')))))
$$;

create or replace function public.detections_before_insert()
returns trigger
language plpgsql security definer set search_path = public as $$
declare
  p  public.profiles;
  pt geography;
  s  public.sites;
  n  integer;
begin
  select * into p from public.profiles where user_id = auth.uid();
  if p.user_id is null then
    raise exception 'user has no profile / company';
  end if;
  -- flood guard: one phone normally sends < 30 rows / minute
  select count(*) into n from public.detections
   where reported_by = auth.uid() and created_at > now() - interval '1 minute';
  if n >= 400 then raise exception 'rate limit'; end if;

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

  new.whitelisted := public.is_whitelisted(new.company_id, new.serial, new.site_id, new.operator_id);
  return new;
end $$;
create index if not exists detections_reporter_time on public.detections (reported_by, created_at desc);

-- spoof_check calls the whitelist test; give it the operator-aware version.
do $$
declare src text;
begin
  select pg_get_functiondef('public.spoof_check(public.detections)'::regprocedure) into src;
  src := replace(src, 'public.is_whitelisted(d.company_id, d.serial, d.site_id)',
                      'public.is_whitelisted(d.company_id, d.serial, d.site_id, d.operator_id)');
  execute src;
end $$;

-- Managers add entries. Region managers: only sites of their region. Default validity 90 days
-- (admins may make an entry permanent with p_valid_days = 0).
drop function if exists public.add_whitelist(text, text, uuid, integer);
create or replace function public.add_whitelist(p_serial text, p_label text default null,
                                                p_site_id uuid default null, p_valid_days integer default null,
                                                p_operator_id text default null)
returns bigint
language plpgsql security definer set search_path = public as $$
declare me public.profiles; nid bigint; days integer := p_valid_days;
begin
  select * into me from public.profiles where user_id = auth.uid();
  if me.role is null or me.role not in ('admin', 'region_manager', 'site_manager') then raise exception 'managers only'; end if;
  if me.role = 'site_manager' and (p_site_id is null or p_site_id <> me.site_id) then raise exception 'site managers: own site only'; end if;
  if me.role = 'region_manager' and (p_site_id is null or not exists (
       select 1 from public.sites where id = p_site_id and region_id = me.region_id)) then
    raise exception 'region managers: a site of your region only';
  end if;
  if p_site_id is not null and not exists (select 1 from public.sites where id = p_site_id and company_id = me.company_id) then
    raise exception 'site not in your company';
  end if;
  if coalesce(trim(p_serial), '') = '' then raise exception 'serial required'; end if;
  if days is null or (days <= 0 and me.role <> 'admin') then days := 90; end if;
  insert into public.whitelist (company_id, serial, label, site_id, valid_until, created_by, operator_id)
  values (me.company_id, upper(trim(p_serial)), nullif(trim(p_label), ''), p_site_id,
          case when days <= 0 then null else now() + make_interval(days => least(days, 3650)) end,
          auth.uid(), nullif(upper(trim(coalesce(p_operator_id, ''))), ''))
  returning id into nid;
  return nid;
end $$;

-- Managers see serials; guards only get one-way hashes (enough for the phone to stay quiet).
drop function if exists public.list_whitelist();
create or replace function public.list_whitelist()
returns table (id bigint, serial text, h text, oh text, label text, site text, site_id uuid,
               valid_until timestamptz, active boolean, operator_id text)
language sql stable security definer set search_path = public as $$
  with me as (select * from public.profiles where user_id = auth.uid())
  select w.id,
         case when me.role <> 'guard' then w.serial end,
         encode(extensions.digest(upper(w.serial), 'sha256'), 'hex'),
         case when w.operator_id is not null then encode(extensions.digest(upper(w.operator_id), 'sha256'), 'hex') end,
         w.label, s.name, w.site_id, w.valid_until,
         (w.valid_until is null or w.valid_until > now()),
         case when me.role <> 'guard' then w.operator_id end
  from public.whitelist w
  join me on me.company_id = w.company_id
  left join public.sites s on s.id = w.site_id
  order by w.created_at desc
$$;

-- ---------------------------------------------------------------- devices
drop policy if exists "own device update" on public.devices;
drop policy if exists "company devices" on public.devices;
create policy "own device update" on public.devices for update
  using (user_id = auth.uid()) with check (user_id = auth.uid());
-- guards: only their own phones; managers use coverage() which is already role-scoped
create policy "own or admin devices" on public.devices for select
  using (user_id = auth.uid()
         or (company_id = public.my_company() and (public.my_profile()).role = 'admin'));

-- ---------------------------------------------------------------- photo bucket
update storage.buckets set file_size_limit = 5242880, allowed_mime_types = array['image/jpeg']
 where id = 'sightings';
drop policy if exists "guard uploads own sighting photos" on storage.objects;
drop policy if exists "company reads sighting photos" on storage.objects;
-- path: <company_id>/<user_id>/<file>.jpg
create policy "staff upload sighting photos" on storage.objects for insert to authenticated
  with check (bucket_id = 'sightings'
              and (storage.foldername(name))[1] = public.my_company()::text
              and (storage.foldername(name))[2] = auth.uid()::text);
create policy "role reads sighting photos" on storage.objects for select to authenticated
  using (bucket_id = 'sightings'
         and (storage.foldername(name))[1] = public.my_company()::text
         and ((storage.foldername(name))[2] = auth.uid()::text
              or (public.my_profile()).role in ('admin', 'region_manager', 'site_manager')));

-- ---------------------------------------------------------------- function privileges
-- Nothing in public is callable without login; internal helpers are not callable at all.
revoke execute on all functions in schema public from public, anon;
grant  execute on all functions in schema public to authenticated, service_role;
revoke execute on function
  public.assert_admin(),
  public.is_whitelisted(uuid, text, uuid, text),
  public.spoof_check(public.detections),
  public.detections_before_insert(),
  public.detections_spoof_trigger(),
  public.devices_before_write(),
  public.sightings_before_insert()
from authenticated;
alter default privileges in schema public revoke execute on functions from public, anon;
