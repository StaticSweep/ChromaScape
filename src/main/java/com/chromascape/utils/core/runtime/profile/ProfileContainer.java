package com.chromascape.utils.core.runtime.profile;

import java.util.List;

/**
 * A container for multiple {@link Profile} objects.
 *
 * <p>This class is primarily used for JSON serialization and deserialization of RuneLite profiles.
 * The {@code profiles.json} file is mapped to a {@code ProfileContainer}, which holds a list of
 * individual {@link Profile} records.
 *
 * <p>Each {@link Profile} in the list represents one profile configuration, including its metadata
 * and settings.
 */
public record ProfileContainer(List<Profile> profiles) {}
