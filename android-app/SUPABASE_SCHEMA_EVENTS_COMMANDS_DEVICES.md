# Supabase — Schéma `devices`, `events`, `commands` (copier/coller)

Ce document te donne un **bloc SQL unique** pour recréer le schéma actuel de tes tables `public.devices`, `public.events`, `public.commands` (colonnes, contraintes, index, fonctions, triggers, commentaires).

## Où coller ?

- **Option A (recommandé)** : Supabase Dashboard → **SQL Editor** → New query → coller le SQL → Run.
- **Option B** : dans une **migration** (Supabase CLI), en collant le même SQL dans un fichier de migration.

## Bloc SQL “clé en main”

> Si tu exécutes dans une base déjà remplie, évite de “DROP” sans sauvegarde.  
> Le script est conçu pour une base neuve (ou un schéma vide).

```sql
begin;

-- Extensions (gen_random_uuid() est fourni par pgcrypto)
create extension if not exists pgcrypto;
-- Optionnel (installé chez toi, mais non requis par ces tables)
create extension if not exists "uuid-ossp";

-- =========
-- TABLES
-- =========

create table if not exists public.devices (
  id text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen_at timestamptz null,
  last_package_name text null,
  status text not null default 'unknown'::text,
  constraint devices_pkey primary key (id)
);

create table if not exists public.events (
  id uuid not null default gen_random_uuid(),
  created_at timestamptz not null default now(),
  device_id text null,
  package_name text null,
  event_value text null,
  event_type text not null,
  constraint android_events_pkey primary key (id),
  constraint android_events_device_id_fkey
    foreign key (device_id)
    references public.devices(id)
    on update cascade
    on delete restrict
);

create table if not exists public.commands (
  id uuid not null default gen_random_uuid(),
  created_at timestamptz not null default now(),
  executed_at timestamptz null,
  device_id text null,
  command_type text not null,
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'pending'::text,
  result jsonb null,
  constraint android_commands_pkey primary key (id),
  constraint android_commands_device_id_fkey
    foreign key (device_id)
    references public.devices(id)
    on update cascade
    on delete restrict
);

-- =========
-- FUNCTIONS (utilisées par les triggers)
-- =========

create or replace function public.update_updated_at_column()
returns trigger
language plpgsql
as $function$
begin
  new.updated_at = now();
  return new;
end;
$function$;

create or replace function public.ensure_device_from_android_event()
returns trigger
language plpgsql
as $function$
begin
  if new.device_id is not null and btrim(new.device_id) <> '' then
    insert into public.devices (id, last_seen_at, last_package_name)
    values (new.device_id, now(), new.package_name)
    on conflict (id) do update
      set last_seen_at = excluded.last_seen_at,
          last_package_name = coalesce(excluded.last_package_name, public.devices.last_package_name);
  end if;
  return new;
end;
$function$;

create or replace function public.ensure_device_from_android_command()
returns trigger
language plpgsql
as $function$
begin
  if new.device_id is not null and btrim(new.device_id) <> '' then
    insert into public.devices (id, last_seen_at)
    values (new.device_id, now())
    on conflict (id) do update
      set last_seen_at = excluded.last_seen_at;
  end if;
  return new;
end;
$function$;

-- =========
-- TRIGGERS
-- =========

drop trigger if exists update_devices_updated_at on public.devices;
create trigger update_devices_updated_at
before update on public.devices
for each row execute function public.update_updated_at_column();

drop trigger if exists tr_android_events_ensure_device on public.events;
create trigger tr_android_events_ensure_device
before insert on public.events
for each row execute function public.ensure_device_from_android_event();

drop trigger if exists tr_android_commands_ensure_device on public.commands;
create trigger tr_android_commands_ensure_device
before insert on public.commands
for each row execute function public.ensure_device_from_android_command();

-- =========
-- INDEXES (hors PK)
-- =========

create index if not exists android_commands_device_status_created_at_idx
  on public.commands using btree (device_id, status, created_at);

create index if not exists android_events_device_created_at_idx
  on public.events using btree (device_id, created_at desc);

create index if not exists idx_android_events_created_at
  on public.events using btree (created_at);

create index if not exists idx_android_events_event_type
  on public.events using btree (event_type);

-- =========
-- COMMENTS
-- =========

comment on table public.devices is 'Android devices ayant installé l’APK (id = ANDROID_ID).';
comment on column public.devices.id is 'ANDROID_ID (Settings.Secure.ANDROID_ID)';

commit;
```

## Notes rapides

- **RLS** : désactivé sur ces 3 tables (aucune policy à recréer).
- Les **PK** créent automatiquement leurs index (donc pas besoin de recréer `*_pkey` à la main).

