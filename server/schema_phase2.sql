-- =====================================================================
--  DroneRadar — Phase 2: countries/sites management + alert routing
--  Run once in Supabase → SQL Editor (after schema.sql).
-- =====================================================================

-- Who am I? (role, company, region, site) — used by the app after login.
create or replace function public.whoami()
returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'has_profile', p.user_id is not null,
    'full_name',   p.full_name,
    'role',        p.role,
    'company',     c.name,
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

create or replace function public.assert_admin()
returns uuid
language plpgsql stable security definer set search_path = public as $$
declare cid uuid;
begin
  select company_id into cid from public.profiles where user_id = auth.uid() and role = 'admin';
  if cid is null then raise exception 'admin only'; end if;
  return cid;
end $$;

-- ---------- Regions / countries ----------
create or replace function public.admin_add_region(p_name text, p_country text, p_timezone text default 'Asia/Dubai')
returns uuid
language plpgsql security definer set search_path = public as $$
declare cid uuid := public.assert_admin(); rid uuid;
begin
  insert into public.regions (company_id, name, country_code, timezone)
  values (cid, p_name, upper(p_country), coalesce(nullif(p_timezone, ''), 'Asia/Dubai'))
  returning id into rid;
  return rid;
end $$;

-- ---------- Sites ----------
create or replace function public.admin_add_site(p_name text, p_region_id uuid, p_lat double precision, p_lon double precision, p_radius_m integer default 500)
returns uuid
language plpgsql security definer set search_path = public as $$
declare cid uuid := public.assert_admin(); sid uuid;
begin
  if p_region_id is not null and not exists (select 1 from public.regions where id = p_region_id and company_id = cid) then
    raise exception 'region not in your company';
  end if;
  insert into public.sites (company_id, region_id, name, center, alert_radius_m)
  values (cid, p_region_id, p_name,
          st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography,
          greatest(50, least(coalesce(p_radius_m, 500), 50000)))
  returning id into sid;
  return sid;
end $$;

create or replace function public.admin_delete_site(p_site_id uuid)
returns void
language plpgsql security definer set search_path = public as $$
declare cid uuid := public.assert_admin();
begin
  delete from public.sites where id = p_site_id and company_id = cid;
end $$;

-- Sites with coordinates as plain numbers (easy for the app).
create or replace function public.list_sites()
returns table (id uuid, name text, region_id uuid, region text, country_code text, lat double precision, lon double precision, radius_m integer)
language sql stable security definer set search_path = public as $$
  select s.id, s.name, s.region_id, r.name, r.country_code,
         st_y(s.center::geometry), st_x(s.center::geometry), s.alert_radius_m
  from public.sites s left join public.regions r on r.id = s.region_id
  where s.company_id = public.my_company()
  order by r.name nulls last, s.name
$$;

-- ---------- Staff ----------
-- Staff sign up in the app themselves; the admin then attaches them by e-mail.
create or replace function public.admin_assign(p_email text, p_role public.user_role, p_full_name text default null,
                                               p_region_id uuid default null, p_site_id uuid default null)
returns void
language plpgsql security definer set search_path = public as $$
declare cid uuid := public.assert_admin(); uid uuid; other uuid; rid uuid := p_region_id;
begin
  select id into uid from auth.users where lower(email) = lower(trim(p_email));
  if uid is null then raise exception 'no account with this email — ask the person to sign up in the app first'; end if;

  select company_id into other from public.profiles where user_id = uid;
  if other is not null and other <> cid then raise exception 'this account belongs to another company'; end if;

  if p_site_id is not null then
    select region_id into rid from public.sites where id = p_site_id and company_id = cid;
    if not found then raise exception 'site not in your company'; end if;
  end if;
  if rid is not null and not exists (select 1 from public.regions where id = rid and company_id = cid) then
    raise exception 'region not in your company';
  end if;

  insert into public.profiles (user_id, company_id, full_name, role, region_id, site_id)
  values (uid, cid, nullif(trim(p_full_name), ''), p_role, rid, p_site_id)
  on conflict (user_id) do update
    set full_name = coalesce(excluded.full_name, public.profiles.full_name),
        role      = excluded.role,
        region_id = excluded.region_id,
        site_id   = excluded.site_id;
end $$;

create or replace function public.admin_unassign(p_email text)
returns void
language plpgsql security definer set search_path = public as $$
declare cid uuid := public.assert_admin(); uid uuid;
begin
  select id into uid from auth.users where lower(email) = lower(trim(p_email));
  if uid = auth.uid() then raise exception 'you cannot remove yourself'; end if;
  delete from public.profiles where user_id = uid and company_id = cid;
end $$;

create or replace function public.list_staff()
returns table (email text, full_name text, role public.user_role, region text, site text, last_seen timestamptz, battery smallint)
language sql stable security definer set search_path = public as $$
  select u.email::text, p.full_name, p.role, r.name, s.name,
         (select max(d.last_seen) from public.devices d where d.user_id = p.user_id),
         (select d.battery from public.devices d where d.user_id = p.user_id order by d.last_seen desc limit 1)
  from public.profiles p
  join auth.users u on u.id = p.user_id
  left join public.regions r on r.id = p.region_id
  left join public.sites   s on s.id = p.site_id
  where p.company_id = public.my_company()
    and (public.my_profile()).role in ('admin', 'region_manager', 'site_manager')
  order by p.role, p.full_name
$$;

-- ---------- Alerts for managers ----------
-- New detections the caller may see (RLS rules), made by OTHER people, with site & reporter names.
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
    and (public.my_profile()).role in ('admin', 'region_manager', 'site_manager')
  order by d.created_at
  limit 500
$$;

-- ---------- Grants ----------
grant execute on function public.whoami(), public.list_sites(), public.list_staff(),
  public.alerts_since(timestamptz) to authenticated;
grant execute on function public.admin_add_region(text, text, text),
  public.admin_add_site(text, uuid, double precision, double precision, integer),
  public.admin_delete_site(uuid),
  public.admin_assign(text, public.user_role, text, uuid, uuid),
  public.admin_unassign(text) to authenticated;
revoke execute on function public.assert_admin() from public, anon;
