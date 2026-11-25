-- =====================================================================
--  DroneRadar — data retention (UAE PDPL: keep personal data only as long as needed)
--  Each company sets how many days detections/sightings/guard positions are kept (default 180).
--  A daily job deletes older rows. Run once after the other files.
-- =====================================================================

create table if not exists public.company_settings (
  company_id      uuid primary key references public.companies on delete cascade,
  retention_days  integer not null default 180 check (retention_days between 7 and 3650),
  updated_at      timestamptz not null default now()
);
alter table public.company_settings enable row level security;
create policy "company settings" on public.company_settings for select using (company_id = public.my_company());
grant select on public.company_settings to authenticated;

create or replace function public.admin_set_retention(p_days integer)
returns void
language plpgsql security definer set search_path = public as $$
declare cid uuid := public.assert_admin();
begin
  insert into public.company_settings (company_id, retention_days) values (cid, p_days)
  on conflict (company_id) do update set retention_days = excluded.retention_days, updated_at = now();
end $$;

create or replace function public.purge_old()
returns json
language plpgsql security definer set search_path = public as $$
declare nd bigint; ns bigint; np bigint;
begin
  with c as (select co.id, coalesce(cs.retention_days, 180) d
               from public.companies co left join public.company_settings cs on cs.company_id = co.id),
       del as (delete from public.detections x using c
                where x.company_id = c.id and x.created_at < now() - make_interval(days => c.d) returning 1)
  select count(*) into nd from del;

  with c as (select co.id, coalesce(cs.retention_days, 180) d
               from public.companies co left join public.company_settings cs on cs.company_id = co.id),
       del as (delete from public.sightings g using c
                where g.company_id = c.id and g.created_at < now() - make_interval(days => c.d) returning g.photo_path)
  select count(*) into ns from del;

  -- photo rows whose sighting is gone (file blobs are removed by the storage service's own cleanup)
  delete from storage.objects o
   where o.bucket_id = 'sightings'
     and not exists (select 1 from public.sightings g where g.photo_path = o.name)
     and o.created_at < now() - interval '1 day';
  get diagnostics np = row_count;

  -- guard positions: last known position only, and only for 30 days
  delete from public.devices where last_seen < now() - interval '30 days';
  delete from public.invites where expires_at < now() - interval '30 days';
  return json_build_object('detections', nd, 'sightings', ns, 'photos', np);
end $$;

revoke execute on function public.purge_old() from public, anon, authenticated;
grant execute on function public.admin_set_retention(integer) to authenticated;

-- Daily at 03:17 (needs the pg_cron extension, available on Supabase cloud and self-hosted).
do $$
begin
  create extension if not exists pg_cron;
  perform cron.unschedule(jobid) from cron.job where jobname = 'droneradar-purge';
  perform cron.schedule('droneradar-purge', '17 3 * * *', 'select public.purge_old()');
exception when others then
  raise notice 'pg_cron not available — run "select public.purge_old();" daily from a scheduler';
end $$;
