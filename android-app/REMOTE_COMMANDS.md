## Remote commands (Supabase) — ultra concis

### Table
- **Table**: `public.commands`
- **Filtre device**: `device_id = Settings.Secure.ANDROID_ID` (côté APK)
- **Workflow**:
  - tu `INSERT` une commande avec `status='pending'`
  - le device exécute
  - le device `PATCH` la ligne en `status='done'` (ou `error`) + `result` + `executed_at`

### Format
- **command_type**: string
- **payload**: jsonb (dépend du type)

### Commandes supportées

#### 1) `tap`
- **payload**: `{ "x": <int>, "y": <int> }`

#### 2) `double_tap`
- **payload**: `{ "x": <int>, "y": <int> }`

#### 3) `long_press`
- **payload**: `{ "x": <int>, "y": <int>, "durationMs": <int?> }` (défaut ~700ms)

#### 4) `swipe`
- **payload**: `{ "x1": <int>, "y1": <int>, "x2": <int>, "y2": <int>, "durationMs": <int?> }` (défaut ~300ms)

#### 5) `global_action`
- **payload**: `{ "action": "BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|POWER_DIALOG|LOCK_SCREEN|TAKE_SCREENSHOT" }`
- Note: `LOCK_SCREEN` / `TAKE_SCREENSHOT` nécessitent Android 9+.

#### 6) `click_node`
- Cherche un node d’accessibilité dont **text** / **content-desc** / **viewId** match `value`, puis clique.
- **payload**: `{ "value": "<string>", "match": "contains|exact" }` (défaut `contains`)
- Optionnel (plus fiable): `ensure_component` / `ensure_package` pour forcer l’app cible avant le click.

#### 7) `set_text`
- Cherche un node (mêmes règles que `click_node`) et tente `ACTION_SET_TEXT`.
- **payload**: `{ "value": "<string>", "text": "<string>", "match": "contains|exact" }` (défaut `contains`)
- Optionnel (plus fiable): `ensure_component` / `ensure_package` pour forcer l’app cible avant le set.

#### 8) `open_app`
- Tente d’ouvrir une app (pratique avant `click_node`/`set_text`).
- **payload** (au choix):
  - `{ "package": "com.foo" }` (launch intent)
  - `{ "component": "com.foo/.MainActivity" }`

### Exemples SQL

```sql
-- TAP
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'tap', '{"x":610,"y":1751}'::jsonb);

-- SWIPE 
-- SWIPE d'un point à un autre (ex: du centre vers le haut)
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'swipe', '{"x1":540,"y1":1600,"x2":540,"y2":500,"durationMs":450}'::jsonb);

-- DOUBLE TAP à une position
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'double_tap', '{"x":540,"y":950}'::jsonb);

-- LONG PRESS sur un point (avec durée personnalisée)
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'long_press', '{"x":350,"y":1200,"durationMs":1200}'::jsonb);

-- Action système globale (ex. bouton retour HOME)
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'global_action', '{"action":"HOME"}'::jsonb);


-- CLICK bouton par texte (contains)
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'click_node', '{"value":"ENVOYER UN TEST","match":"contains","ensure_component":"com.andtracker/.MainActivity"}'::jsonb);

-- SET_TEXT dans un champ (match sur hint/label/text selon l'écran)
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'set_text', '{"value":"Tape ici","match":"contains","text":"bonjour","ensure_component":"com.andtracker/.MainActivity"}'::jsonb);

-- Ouvrir l'app puis cliquer un bouton par texte
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'open_app', '{"component":"com.andtracker/.MainActivity"}'::jsonb);
insert into public.commands (device_id, command_type, payload)
values ('<device_id>', 'click_node', '{"value":"ENVOYER UN TEST","match":"contains"}'::jsonb);
```

