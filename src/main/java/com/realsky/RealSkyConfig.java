package com.realsky;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in config/realistic-sky.json. Edit the file, then run /realsky reload in game. */
public class RealSkyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;

    /** Observer latitude in degrees. Tilts the sun's path and the star field; 0 = sun passes straight overhead. */
    public float latitude = 35.0f;
    /** Enables seasons: the sun's declination swings +-23.4 degrees over seasonLengthDays in-game days. */
    public boolean seasons = false;
    public float seasonLengthDays = 96.0f;

    /** Angular radius of the sun and moon discs in degrees (real value is about 0.27). */
    public float sunRadiusDeg = 0.45f;
    public float moonRadiusDeg = 0.5f;

    /** Volumetric clouds. coverage 0..1, quality = ray-march steps (24 = fast, 48 = default, 96 = ultra). */
    public boolean clouds = true;
    public float cloudCoverage = 0.35f;
    public int cloudQuality = 48;
    public float cloudWindSpeed = 1.0f;
    /** Hide vanilla blocky clouds while this mod's sky is active. */
    public boolean hideVanillaClouds = true;

    public float starBrightness = 1.0f;
    public float milkyWayBrightness = 1.0f;
    public float nebulaStrength = 1.0f;
    /** How bright the night sky may get (scales the maximum exposure). */
    public float nightBrightness = 1.0f;
    public float exposure = 1.0f;

    /** Tint vanilla distance fog to the new horizon colour so terrain blends into the sky. */
    public boolean matchFog = true;
    /** Draw the area below the horizon as a reflective ocean surface. */
    public boolean reflectiveHorizon = true;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("realistic-sky.json");
    }

    public static RealSkyConfig load() {
        Path p = path();
        RealSkyConfig cfg = null;
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                cfg = GSON.fromJson(r, RealSkyConfig.class);
            } catch (Exception e) {
                RealSky.LOGGER.error("Could not read {}, using defaults", p, e);
            }
        }
        if (cfg == null) cfg = new RealSkyConfig();
        cfg.cloudQuality = Math.max(8, Math.min(192, cfg.cloudQuality));
        cfg.cloudCoverage = Math.max(0f, Math.min(1f, cfg.cloudCoverage));
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path())) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            RealSky.LOGGER.error("Could not save config", e);
        }
    }
}
