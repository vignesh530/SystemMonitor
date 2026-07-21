# Changelog

## v2.1 — July 2026

### Bug Fixes

**1. Active window not updating (Add-Type re-declaration crash)**
- Root cause: `Add-Type -Name 'FW'` fails on second call with "type already exists" error in PowerShell.
- Fix: Added `PSTypeName` guard — checks if type exists before calling `Add-Type`. No crash, no blank output.

**2. Volume always showing "--" (same Add-Type crash)**
- Root cause: `Add-Type -Name 'WV'` for `waveOutGetVolume` crashed on re-run same as above.
- Fix: Same `PSTypeName` guard pattern applied to `WinVol` type .

**3. Top CPU showing error string instead of app names**
- Root cause: `Join-String` cmdlet does not exist in PowerShell 5.1 (Windows default). It was introduced in PS 7.
- Fix: Replaced `| Join-String -Sep ', '` with `$apps -join ', '` which works in all PS versions.

**4. Weather showing "Bengaluru Urban A°C Wind:km/hkm/h" (encoding + parse bug)**
- Root cause 1: `°` degree symbol was being garbled by Windows-1252 encoding when reading PowerShell output.
- Root cause 2: `temperature` and `windspeed` values were not being parsed — extractJson was returning empty string.
- Fix 1: All HTTP streams now read as explicit `StandardCharsets.UTF_8` byte arrays.
- Fix 2: Replaced degree symbol `°C` with plain `C` to avoid all encoding issues entirely.
- Fix 3: `httpGet()` utility now uses `.readAllBytes()` with UTF-8 decode, ensuring JSON is read fully.

**5. Location always showing Bangalore instead of actual city**
- Root cause: ip-api.com resolves IP to ISP's city (Bangalore for Airtel/BSNL users in Tamil Nadu).
- Fix: Added dual-API fallback — ip-api.com primary, ipinfo.io fallback. Both APIs attempted; first non-empty city used. Also added `status` field check to confirm ip-api success.

**6. Labels repainting every 200ms causing UI flicker**
- Fix: Added per-field last-value tracking (`lastVolume`, `lastBrightness`, `lastActiveApp`, etc.). Labels only repainted when value actually changes. Eliminates unnecessary Swing repaints.

**7. PowerShell output included error text mixed with values**
- Fix: `redirectErrorStream(true)` ensures stderr is included so we can detect error strings. Wrapped all PS calls in `try{}catch{}` blocks inside the PS script itself, returning safe defaults.

### Improvements

- All HTTP reads now use `readAllBytes()` + explicit UTF-8, removing encoding bugs permanently.
- `runPS()` now reads output as `UTF-8` byte array instead of platform default charset.
- `ProcessBuilder` used instead of `Runtime.exec()` for cleaner process management.
- Thread pool uses named daemon threads (`SysMon-BG`) for cleaner debugging.
- `setCoalesce(true)` on Swing timer prevents timer event pile-up under load.
