# Realistic Sky

A client-side Fabric mod for Minecraft 1.21.11 that replaces the overworld sky with:

- a physically based atmosphere (Rayleigh + Mie scattering, ozone) with real sunsets and twilight
- volumetric, ray-marched cumulus clouds lit by the sun or moon
- a limb-darkened sun and a phased moon with maria
- ~41,000 real stars at their catalogue positions, coloured by temperature, with twinkling and extinction
- the Milky Way traced from real survey outlines, with dust lanes, nebulae, Andromeda and the Magellanic Clouds
- a reflective ocean horizon and distance fog tinted to match the sky

Requires Fabric API. Stays out of the way when an Iris shader pack is active.

## Commands
- `/realsky reload` — reload `config/realistic-sky.json` and the shaders
- `/realsky toggle` — switch between this sky and vanilla
- `/realsky clouds <0-1>` — set cloud coverage
- `/realsky status`

Shaders can be edited live: copy files from `src/main/resources/assets/realsky/shaders/` into
`config/realsky/shaders/`, edit them, and run `/realsky reload`.

## Building
Every push builds the jar with GitHub Actions (Actions tab → latest run → `realistic-sky-jar`).
Locally: `./gradlew build` (needs JDK 21+).
