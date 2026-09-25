package com.chromascape.utils.core.runtime.profile;

/**
 * Represents a RuneLite profile configuration.
 *
 * <p>Each profile corresponds to a {@code .properties} file in the RuneLite profiles directory and
 * an entry in {@code profiles.json}. This record stores the metadata RuneLite uses to identify and
 * manage the profile.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code id} – unique identifier for the profile (used in the filename)
 *   <li>{@code name} – display name of the profile
 *   <li>{@code sync} – whether this profile is synced via RuneLite cloud
 *   <li>{@code active} – whether this profile is currently selected
 *   <li>{@code rev} – revision number for internal tracking
 *   <li>{@code defaultForRsProfiles} – array of RuneLite internal profile IDs that this profile is
 *       the default for
 * </ul>
 */
public record Profile(
    long id, String name, boolean sync, boolean active, int rev, String[] defaultForRsProfiles) {}
