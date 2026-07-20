# Find It — agent guide

Metal detecting field companion (maps, find log, grids, terrain relief, export).

## Identity

| | |
|---|---|
| **Root** | Clone path (e.g. `C:\Users\Austin\Find-It-App`) |
| **applicationId** | `com.strobingn.findit` |
| **Module** | `:app` |
| **UI** | Compose + Material 3 + Navigation 3 |
| **GitHub** | https://github.com/Strobingn/https-github.com-Strobingn-Find-It-App |
| **version** | `0.1.0-metal` |

## Build

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

SDK via `local.properties` (`sdk.dir=...`).

## Feature map (code)

| Feature | Code |
|---|---|
| Find log | `ui/finds`, `data/HuntRepository` |
| Grid planner | `ui/grid`, `SearchGrid` |
| Terrain / disturbance | `data/terrain/TerrainAnalyzer`, `ui/terrain` |
| Historical layers | `data/historical`, `ui/historical` |
| Team LOS | `data/team`, `ui/team` |
| Export | `data/export/HuntExporter`, `ui/export` |
| Offline | `data/offline/OfflineCache` |
| Battery | `data/power/BatteryOptimizer` |
| AR shell | `ui/ar` |

## Related apps

- **Viewshade** (`Viewshading-app`) — viewshed / elevation engine to reuse for real team LOS  
- **FieldOps** — field notes / Maps console practices  

## Conventions

1. Prefer offline-first paths; demos must work without Maps keys.  
2. Never commit `local.properties`, keystores, or secrets.  
3. Keep unit tests for export + terrain math green.  
