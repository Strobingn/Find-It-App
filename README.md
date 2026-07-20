# Find It

Metal detecting field kit for Android — log finds, plan search grids, read terrain relief, toggle historical layers, coordinate with partners, and export GPX/KML/CSV.

Built for detectorists who want **where people used to live** and **subtle ground disturbance**, not just a basemap pin.

## Features (v0.1)

See [ROADMAP.md](ROADMAP.md) for the full priority table. Wired today:

- Structured find log (metal type, depth, notes, photo URI)
- Search grid planner with covered-cell tracking
- Offline terrain tools (hillshade, Sky View Factor, openness, disturbance)
- Historical / relief layer toggles
- Team visibility (open-field LOS stub)
- Export GPX · KML · CSV via share sheet
- Low-power mode for long hunts
- Offline JSON + export cache

## Build

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Open in Android Studio or deploy:

```powershell
.\gradlew.bat :app:installDebug
```

## License / origin

Scaffolded with Android CLI empty-activity (Compose). Product roadmap is metal-detector specific.
