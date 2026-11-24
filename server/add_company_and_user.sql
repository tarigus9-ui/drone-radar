-- =====================================================================
--  Add a company, a country/region, a site, and link a user to them.
--  1) First create the user: Supabase → Authentication → Users → Add user
--     (email + password, tick "Auto Confirm User").
--  2) Change the values marked  <<< below, then Run.
-- =====================================================================

with c as (
  insert into public.companies (name)
  values ('شركة الحراسة التجريبية')                       -- <<< company name
  returning id
), r as (
  insert into public.regions (company_id, name, country_code, timezone)
  select id, 'الإمارات - دبي', 'AE', 'Asia/Dubai' from c   -- <<< region
  returning id, company_id
), s as (
  insert into public.sites (company_id, region_id, name, center, alert_radius_m)
  select company_id, id, 'الموقع الأول',                    -- <<< site name
         st_setsrid(st_makepoint(55.3770, 25.1185), 4326)::geography,  -- <<< (longitude, latitude)
         800                                               -- <<< radius in metres
  from r
  returning id, company_id, region_id
)
insert into public.profiles (user_id, company_id, full_name, role, region_id, site_id)
select u.id, s.company_id, 'المدير', 'admin', s.region_id, s.id   -- <<< name, role
from auth.users u, s
where u.email = 'admin@example.com';                       -- <<< the user's email

-- Add more guards to the same company later:
-- insert into public.profiles (user_id, company_id, full_name, role)
-- select u.id, (select id from public.companies where name = 'شركة الحراسة التجريبية'), 'حارس 1', 'guard'
-- from auth.users u where u.email = 'guard1@example.com';
