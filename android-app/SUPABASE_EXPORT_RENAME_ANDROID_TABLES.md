# Supabase — Export/rename “Android tracker” tables (copier-coller)

Objectif : remplacer les tables `public.android_commands` et `public.android_events` par des noms plus courts `public.commands` et `public.events`, et garder `public.devices`.

Ce document est conçu pour être **copié-collé dans le SQL Editor Supabase**.

---

## 0) Prérequis & point de vigilance (important)

### Tables actuelles (confirmées via Supabase MCP)

- **`public.commands`**  
  Colonnes : `id uuid pk`, `created_at timestamptz`, `executed_at timestamptz null`, `device_id text null (fk -> devices.id)`, `command_type text`, `payload jsonb default '{}'`, `status text default 'pending'`, `result jsonb null`  
  Trigger : `tr_android_commands_ensure_device` → `ensure_device_from_android_command()`

- **`public.events`**  
  Colonnes : `id uuid pk`, `created_at timestamptz`, `device_id text null (fk -> devices.id)`, `package_name text null`, `event_value text null`, `event_type text`  
  Trigger : `tr_android_events_ensure_device` → `ensure_device_from_android_event()`

- **`public.devices`**  
  Colonnes : `id text pk` (ANDROID_ID), `created_at`, `updated_at`, `last_seen_at null`, `last_package_name null`, `status text default 'unknown'`  
  Trigger : `update_devices_updated_at` → `update_updated_at_column()`

### Attention : conflit possible avec `public.events`

Dans ta base, **il existe déjà une table `public.events`** (non liée à l’Android tracker).  
Donc **le renommage `android_events` → `events` échouera** tant que `public.events` existe.

Tu as donc 2 chemins :

- **Option A (recommandée si tu veux garder ta table existante `public.events`)** : renommer `android_events` vers un autre nom (ex : `device_events`) et mettre à jour l’app en conséquence.
- **Option B (ce que tu as demandé : `events`)** : libérer le nom `public.events` (en renommant/déplaçant l’ancienne table), puis renommer `android_events` → `events`.

Le script ci-dessous implémente **Option B**, mais **s’arrête proprement** si `public.events` existe, pour éviter une action destructive non voulue.

---

## 1) Script “rename” (Option B) — à coller dans Supabase SQL Editor

> Ce script :
> - renomme `android_commands` → `commands`
> - renomme `android_events` → `events` (**uniquement si `public.events` n’existe pas**)
> - laisse `devices` inchangé
> - conserve triggers, FKs, et fonctions (Postgres met à jour les dépendances par OID)

```sql
begin;

-- Sécurité: on refuse d'écraser une table existante.
do $$
begin
  if to_regclass('public.commands') is not null then
    raise exception 'Abort: public.commands existe déjà.';
  end if;

  if to_regclass('public.events') is not null then
    raise exception 'Abort: public.events existe déjà. Voir Option A ou renomme ta table existante avant.';
  end if;
end $$;

-- 1) android_commands -> commands
alter table public.android_commands rename to commands;

-- 2) android_events -> events
alter table public.android_events rename to events;

commit;
```

---

## 2) Option A (si tu dois conserver `public.events`)

Si tu veux garder ta table existante `public.events`, utilise par exemple `device_events` :

```sql
begin;

do $$
begin
  if to_regclass('public.commands') is not null then
    raise exception 'Abort: public.commands existe déjà.';
  end if;

  if to_regclass('public.device_events') is not null then
    raise exception 'Abort: public.device_events existe déjà.';
  end if;
end $$;

alter table public.android_commands rename to commands;
alter table public.android_events rename to device_events;

commit;
```

Dans ce cas, il faut que l’APK envoie ses events sur `/rest/v1/device_events` au lieu de `/rest/v1/events`.

---

## 3) Vérifications rapides après migration

À coller dans le SQL Editor :

```sql
select 'commands' as table, count(*) from public.commands
union all
select 'events' as table, count(*) from public.events
union all
select 'devices' as table, count(*) from public.devices;
```

Et pour vérifier les triggers :

```sql
select event_object_table, trigger_name, action_timing, event_manipulation, action_statement
from information_schema.triggers
where trigger_schema='public'
  and event_object_table in ('commands','events','devices')
order by event_object_table, trigger_name;
```

---

## 4) Rollback (si tu es encore dans la même session et que ça a échoué)

Si le script a échoué **avant** le `commit`, un simple :

```sql
rollback;
```

Si tu as déjà `commit`, tu peux renommer dans l’autre sens (à adapter selon l’option choisie) :

```sql
begin;
alter table public.commands rename to android_commands;
alter table public.events rename to android_events;
commit;
```

