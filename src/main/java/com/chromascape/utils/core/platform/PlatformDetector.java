package com.chromascape.utils.core.platform;

/**
 * Detects the current operating system and provides platform-specific information.
 * This is the foundation for the cross-platform abstraction layer.
 */
public class PlatformDetector {
    
    public enum Platform {
        WINDOWS,
        MACOS,
        LINUX,
        UNKNOWN
    }
    
    private static final Platform CURRENT_PLATFORM = detectPlatform();
    
    /**
     * Detects the current operating system.
     */
    private static Platform detectPlatform() {
        String osName = System.getProperty("os.name").toLowerCase();
        
        if (osName.contains("win")) {
            return Platform.WINDOWS;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return Platform.MACOS;
        } else if (osName.contains("nux") || osName.contains("nix")) {
            return Platform.LINUX;
        } else {
            return Platform.UNKNOWN;
        }
    }
    
    /**
     * Returns the current platform.
     */
    public static Platform getCurrentPlatform() {
        return CURRENT_PLATFORM;
    }
    
    /**
     * Checks if the current platform is Windows.
     */
    public static boolean isWindows() {
        return CURRENT_PLATFORM == Platform.WINDOWS;
    }
    
    /**
     * Checks if the current platform is macOS.
     */
    public static boolean isMacOS() {
        return CURRENT_PLATFORM == Platform.MACOS;
    }
    
    /**
     * Checks if the current platform is Linux.
     */
    public static boolean isLinux() {
        return CURRENT_PLATFORM == Platform.LINUX;
    }
    
    /**
     * Returns a human-readable description of the current platform.
     */
    public static String getPlatformDescription() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version");
    }
}
