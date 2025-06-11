package com.chromascape.utils.domain;

import com.chromascape.utils.core.screen.vision.CvUtils;
import com.chromascape.utils.core.screen.window.ScreenCapture;
import com.chromascape.utils.core.screen.window.WindowHandler;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_UNCHANGED;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;

public class Zones {

    private final CvUtils cvUtils;

    private final ScreenCapture screenCapture;

    private final WindowHandler windowHandler;

    private final String[] zoneTemplates = {
            "src/images/UI/minimap.png",
            "src/images/UI/inv.png",
            "src/images/UI/chat.png",
            "src/images/UI/minimap_fixed.png"};

    private final Rectangle[] zoneLocations = {
            null,
            null,
            null,
            null};

    private final Map<String, Rectangle> minimap = new HashMap<>();
    private final Map<String, Rectangle> minimapFixed = new HashMap<>();
    private final Map<String, Rectangle> ctrlPanel = new HashMap<>();
    private final Map<String, Rectangle> chatTabs = new HashMap<>();
    private final List<Rectangle> inventorySlots = new ArrayList<>();


    public Zones(CvUtils cvUtils, ScreenCapture screenCapture, WindowHandler windowHandler) throws Exception {
        this.cvUtils = cvUtils;
        this.screenCapture = screenCapture;
        this.windowHandler = windowHandler;
        mapAllZones();
    }

    private void zoneMapper() throws Exception {
        for (int i = 0; i < zoneTemplates.length; i++) {
            Mat template = imread(zoneTemplates[i], IMREAD_UNCHANGED);
            BufferedImage screenCap = screenCapture.captureWindow(windowHandler.getTargetWindow());
            Mat base = Java2DFrameUtils.toMat(screenCap);
            zoneLocations[i] = cvUtils.patternMatch(template, base, 0.9);
        }
    }

    private void mapAllZones() throws Exception {
        zoneMapper();
        mapMinimap();
        mapFixedMinimap();
        mapCtrlPanel();
        mapChat();
        mapInventory();
    }

    private void mapMinimap() {
        if (zoneLocations[0] == null) return;

        Rectangle zone = zoneLocations[0];

        minimap.put("specOrb", new Rectangle(zone.x + 62, zone.y + 143, 19, 19));
        minimap.put("specText", new Rectangle(zone.x + 36, zone.y + 151, 19, 12));

        minimap.put("runOrb", new Rectangle(zone.x + 40, zone.y + 118, 18, 20));
        minimap.put("runText", new Rectangle(zone.x + 14, zone.y + 126, 19, 12));

        minimap.put("prayerOrb", new Rectangle(zone.x + 30, zone.y + 86, 19, 19));
        minimap.put("prayerText", new Rectangle(zone.x + 4, zone.y + 94, 19, 12));

        minimap.put("hpOrb", new Rectangle(zone.x + 30, zone.y + 52, 19, 19));
        minimap.put("hpText", new Rectangle(zone.x + 4, zone.y + 60, 19, 12));

        minimap.put("compass", new Rectangle(zone.x + 39, zone.y + 8, 24, 24));
    }

    private void mapFixedMinimap() {
        if (zoneLocations[3] == null) return;

        Rectangle zone = zoneLocations[3];

        minimapFixed.put("specOrb", new Rectangle(zone.x + 62, zone.y + 137, 18, 20));
        minimapFixed.put("specText", new Rectangle(zone.x + 36, zone.y + 146, 19, 12));

        minimapFixed.put("runOrb", new Rectangle(zone.x + 40, zone.y + 112, 18, 20));
        minimapFixed.put("runText", new Rectangle(zone.x + 14, zone.y + 121, 19, 12));

        minimapFixed.put("prayerOrb", new Rectangle(zone.x + 29, zone.y + 80, 18, 20));
        minimapFixed.put("prayerText", new Rectangle(zone.x + 4, zone.y + 89, 19, 12));

        minimapFixed.put("hpOrb", new Rectangle(zone.x + 29, zone.y + 46, 18, 20));
        minimapFixed.put("hpText", new Rectangle(zone.x + 4, zone.y + 55, 19, 12));

        minimapFixed.put("compass", new Rectangle(zone.x + 32, zone.y + 7, 23, 25));
    }

    private void mapCtrlPanel() {
        if (zoneLocations[1] == null) return;

        Rectangle zone = zoneLocations[1];

        // Top row
        ctrlPanel.put("combatTab", new Rectangle(zone.x + 7, zone.y + 6, 26, 24));
        ctrlPanel.put("skillsTab", new Rectangle(zone.x + 41, zone.y + 2, 26, 28));
        ctrlPanel.put("summaryTab", new Rectangle(zone.x + 74, zone.y + 2, 26, 28));
        ctrlPanel.put("inventoryTab", new Rectangle(zone.x + 107, zone.y + 2, 26, 28));
        ctrlPanel.put("equipmentTab", new Rectangle(zone.x + 140, zone.y + 2, 26, 28));
        ctrlPanel.put("prayerTab", new Rectangle(zone.x + 173, zone.y + 2, 26, 28));
        ctrlPanel.put("spellbookTab", new Rectangle(zone.x + 206, zone.y + 6, 27, 24));

        // Bottom row
        ctrlPanel.put("channelTab", new Rectangle(zone.x + 7, zone.y + 300, 28, 25));
        ctrlPanel.put("friendsTab", new Rectangle(zone.x + 41, zone.y + 300, 26, 30));
        ctrlPanel.put("accountTab", new Rectangle(zone.x + 74, zone.y + 300, 26, 30));
        ctrlPanel.put("logoutTab", new Rectangle(zone.x + 107, zone.y + 300, 26, 30));
        ctrlPanel.put("settingsTab", new Rectangle(zone.x + 140, zone.y + 300, 26, 30));
        ctrlPanel.put("emotesTab", new Rectangle(zone.x + 173, zone.y + 300, 26, 30));
        ctrlPanel.put("musicTab", new Rectangle(zone.x + 206, zone.y + 300, 27, 25));

        // Main inventory area
        ctrlPanel.put("inventoryPanel", new Rectangle(zone.x + 28, zone.y + 35, 183, 261));
    }

    private void mapChat(){
        if (zoneLocations[2] == null) return;

        Rectangle zone = zoneLocations[2];
        String[] tabNames = {"All", "Game", "Public", "Private", "Channel", "Clan", "Group"};

        int x = 5;
        int y = 143;
        for (int i = 0; i < 7; i++) {
            chatTabs.put(tabNames[i], new Rectangle(zone.x + x, zone.y + y, 52, 19));
            x += 62;
        }
        chatTabs.put("Chat", new Rectangle(zone.x + 5, zone.y + 5, 506, 129));
    }

    private void mapInventory() {
        if (zoneLocations[1] == null) return;

        Rectangle zone = zoneLocations[1];

        int slotWidth = 36;
        int slotHeight = 32;
        int gapX = 6;
        int gapY = 4;

        int y = zone.y + 44;
        for (int i = 0; i < 7; i++) {
            int x = zone.x + 40;
            for (int j = 0; j < 4; j++) {
                inventorySlots.add(new Rectangle(x, y, slotWidth, slotHeight));
                x += slotWidth + gapX;
            }
            y += slotHeight + gapY;
        }
    }

    public Map<String, Rectangle> getMinimap() {
        return minimap;
    }

    public Map<String, Rectangle> getMinimapFixed() {
        return minimapFixed;
    }

    public Map<String, Rectangle> getCtrlPanel() {
        return ctrlPanel;
    }

    public Map<String, Rectangle> getChatTabs() {
        return chatTabs;
    }

    public List<Rectangle> getInventorySlots() {
        return inventorySlots;
    }
}
