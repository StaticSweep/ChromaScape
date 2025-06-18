package com.chromascape.utils.domain.zones;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SubZoneMapper {

    public Map<String, Rectangle> mapMinimap(Rectangle zone) {
        if (zone != null) {

            Map<String, Rectangle> minimap = new HashMap<>();

            minimap.put("specOrb", new Rectangle(zone.x + 62, zone.y + 143, 19, 19));
            minimap.put("specText", new Rectangle(zone.x + 36, zone.y + 151, 19, 12));
            minimap.put("runOrb", new Rectangle(zone.x + 40, zone.y + 118, 18, 20));
            minimap.put("runText", new Rectangle(zone.x + 14, zone.y + 126, 19, 12));
            minimap.put("prayerOrb", new Rectangle(zone.x + 30, zone.y + 86, 19, 19));
            minimap.put("prayerText", new Rectangle(zone.x + 4, zone.y + 94, 19, 12));
            minimap.put("hpOrb", new Rectangle(zone.x + 30, zone.y + 52, 19, 19));
            minimap.put("hpText", new Rectangle(zone.x + 4, zone.y + 60, 19, 12));
            minimap.put("compass", new Rectangle(zone.x + 39, zone.y + 8, 24, 24));
            return minimap;
        } else {
            System.out.println("No minimap found");
            return null;
        }
    }

    public Map<String, Rectangle> mapFixedMinimap(Rectangle zone) {
        if (zone != null) {

            Map<String, Rectangle> minimap = new HashMap<>();

            minimap.put("specOrb", new Rectangle(zone.x + 62, zone.y + 137, 18, 20));
            minimap.put("specText", new Rectangle(zone.x + 36, zone.y + 146, 19, 12));
            minimap.put("runOrb", new Rectangle(zone.x + 40, zone.y + 112, 18, 20));
            minimap.put("runText", new Rectangle(zone.x + 14, zone.y + 121, 19, 12));
            minimap.put("prayerOrb", new Rectangle(zone.x + 29, zone.y + 80, 18, 20));
            minimap.put("prayerText", new Rectangle(zone.x + 4, zone.y + 89, 19, 12));
            minimap.put("hpOrb", new Rectangle(zone.x + 29, zone.y + 46, 18, 20));
            minimap.put("hpText", new Rectangle(zone.x + 4, zone.y + 55, 19, 12));
            minimap.put("compass", new Rectangle(zone.x + 32, zone.y + 7, 23, 25));
            return minimap;
        } else {
            System.out.println("No fixed minimap found");
            return null;
        }
    }

    public Map<String, Rectangle> mapCtrlPanel(Rectangle zone) {
        if (zone != null) {

            Map<String, Rectangle> ctrlPanel = new HashMap<>();
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
            return ctrlPanel;
        } else {
            System.out.println("No ctrlPanel found");
            return null;
        }
    }

    public Map<String, Rectangle> mapChat(Rectangle zone){
        if (zone != null) {

            Map<String, Rectangle> chatTabs = new HashMap<>();

            String[] tabNames = {"All", "Game", "Public", "Private", "Channel", "Clan", "Group"};

            int x = 5;
            int y = 143;
            for (int i = 0; i < 7; i++) {
                chatTabs.put(tabNames[i], new Rectangle(zone.x + x, zone.y + y, 52, 19));
                x += 62;
            }
            chatTabs.put("Chat", new Rectangle(zone.x + 5, zone.y + 5, 506, 129));
            return chatTabs;
        } else {
            System.out.println("No Chat found");
            return null;
        }
    }

    public List<Rectangle> mapInventory(Rectangle zone) {
        if (zone != null) {

            List<Rectangle> inventorySlots = new ArrayList<>();

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
            return inventorySlots;
        } else {
            System.out.println("No Inventory found");
            return null;
        }
    }

    public Rectangle mapFixedGameView(Rectangle zone){
        if (zone != null) {
            return new Rectangle(zone.x + 4, zone.y + 4, 511, 333);
        } else {
            System.out.println("GameView is not found because minimap is null");
            return null;
        }
    }

}
