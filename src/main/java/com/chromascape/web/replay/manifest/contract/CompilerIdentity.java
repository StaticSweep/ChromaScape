package com.chromascape.web.replay.manifest.contract;

/** Compiler version reference within a manifest. */
public record CompilerIdentity(String version, int schemaVersion) {}
