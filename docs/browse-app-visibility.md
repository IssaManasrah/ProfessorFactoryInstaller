# Browse Apps visibility control (Professor Installer)

## Behavior
- Admin panel: in **Apps > Edit App**, add an independent switch labeled **"إظهار في تصفح التطبيقات"** (Show in Browse Apps).
- Default: **ON** for all existing and newly added apps, preserving existing behavior.
- Turning the switch OFF hides that app **only** from the **"تصفح التطبيقات"** (Browse Apps) view in Professor Installer.
- A hidden app remains in the API catalog, available to programming model groups, USB/online installation queues and support-code operations. **Do not delete or filter it out of `apps`.**
- The flag applies on the next catalog refresh when Browse Apps is opened; no APK rebuild is needed for subsequent toggle changes after a client with this feature is installed.
- The flag is not a security boundary: it hides a discovery card, not the download URL or an already-installed app.

## API contract
The Android client reads the existing `GET /professor-installer/api/catalog.php?country=JO` response. Every `apps[]` item may include:

```json
{
  "id": "example-app",
  "name": "Example App",
  "required": false,
  "showInBrowser": false
}
```

- Emit a JSON **boolean**, not a string or numeric flag.
- An omitted `showInBrowser` field is interpreted as **true**, so legacy responses work unchanged.
- All model groups and technical support jobs continue to use the full app collection regardless of `showInBrowser`.

## Required server/admin implementation
The server PHP source and admin panel for `shaikhalkar.com/professor-installer/` are not in this Android repository, so they must be updated separately:

1. Store a per-app `show_in_browser` boolean with default `true`; migrate existing app records to `true`.
2. Add an authenticated switch to the existing app create/edit form and persist it through the admin save API (including CSRF/permission checks as in the current panel).
3. Include `showInBrowser: (bool) show_in_browser` on **every** app object in `api/catalog.php`, including apps hidden from browsing.
4. Make the setting independently editable without affecting app download, model-group membership, versions, country filters or support jobs.
5. Test ON/OFF for multiple apps and countries, with a previously installed Android app as well as a new one. Existing Android app versions that do not support the flag continue showing all apps until upgraded.

## Android implementation
- `Models.AppInfo.showInBrowser`: defaults to `true`, read from API JSON.
- `Models.Catalog.browseApps()`: returns only browse-visible apps.
- `MainActivity.showApps()`: renders that list; it shows an empty state when all apps are hidden.
- `showModels()`, `startGroup()`, and support-code flows remain unchanged.

## Acceptance checks
1. An API response without the field still shows all apps.
2. `showInBrowser=false` hides the app from Browse Apps.
3. `showInBrowser=true` shows the app.
4. A hidden app still installs through a programming group and still executes via a support code.
5. When every app is hidden, Browse Apps shows "لا توجد تطبيقات ظاهرة حاليًا".
6. No installed app is uninstalled or otherwise modified merely by hiding it.
