# Pokésearch Overlay v0.3 Alpha

Independent Android package com.lucianotoscano.pokesearchoverlay.

## Changes
- Vertical ultra-narrow overlay dock (86dp) with stacked controls.
- Shows all 10 max search results at once, without scrolling, with authentic IV% on each Pokémon icon row.
- Each icon opens a separate compact detail window adjacent to the dock with name, form, size, gender, CP, level, IV, moves, location, despawn and Get coords where available.
- Detail window does not make the dock grow or hide any of the ten results.
- Dynamic row sizing based on device viewport; no false 100% badges.
- Drag by grip, minimize to bubble and close.
- Searches powered by authenticated PoGoCoordinates session, automatic 5min refresh.

## Limitations
The app does not currently implement a guaranteed backend/API integration; it reads the site DOM. Login and site filter/Get coords behavior must be tested on device. Website changes can break selectors. Actual coordinates appear only if the site exposes them. A blurred material across other apps is device/API-dependent; transparency is guaranteed.

## Installation
If debug signing key differs from your existing build, uninstall the previous alpha before installing.
