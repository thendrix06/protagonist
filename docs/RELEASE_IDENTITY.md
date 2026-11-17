# Release identity: what is provisional

| Item | Current value | Status |
|---|---|---|
| Display / launcher name | `Protagonist` (`app/src/main/res/values/strings.xml` → `app_name`) | Working name. |
| Application ID | `com.protagonist.app` | **Provisional.** Legal ownership of the `protagonist.com` reverse-domain namespace has **not** been confirmed. Do not assume it is available or registered. |
| Kotlin namespace (`android.namespace`) | `com.protagonist.app` | Provisional; it can differ from the application ID. |
| Launcher icon | Generic reticle placeholder (`ic_launcher_foreground.xml`) | Not a logo. Replace once brand assets exist. |
| Signing | None configured; debug keystore only | No production signing identity exists by design. |
| Corporate entity, trademark, domain | None asserted | Do not add them without legal confirmation. |

## Before any Play Store or enterprise (MDM) deployment

1. Confirm the legal owner of the release identifier, then set `applicationId`. Changing it later breaks updates
   for installed devices, so decide once.
2. Confirm trademark clearance for the display name in the target markets, or change `app_name`. No code changes
   are needed because the brand appears only in resources and docs.
3. Create a production signing key under the owner's key-management policy (Play App Signing or enterprise
   PKI). Add a `signingConfig` that reads from a secure store, never from the repo.
4. Replace the placeholder icon and add store assets.
5. Set `versionCode` / `versionName` policy, and remove the `-sim` suffix once Core integration ships.
6. Point `CONTROL_URL` (or a runtime provisioning flow) at the production Core endpoint, and implement the
   `CredentialProvider` (see `HARDWARE_BACKLOG.md`).
7. Review the data-safety declaration. No imagery is persisted, backups are disabled
   (`allowBackup=false` plus data-extraction rules), and logs are redacted.
8. For enterprise distribution, configure managed-configuration keys for the endpoint and credentials instead of
   build-time constants.
