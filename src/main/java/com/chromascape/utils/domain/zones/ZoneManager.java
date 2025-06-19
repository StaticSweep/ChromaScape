package com.chromascape.utils.domain.zones;

import com.chromascape.utils.core.screen.vision.MaskZones;
import com.chromascape.utils.core.screen.vision.TemplateMatching;
import com.chromascape.utils.core.screen.vision.DisplayImage;
import com.chromascape.utils.core.screen.window.ScreenCapture;
import com.chromascape.utils.core.screen.window.WindowHandler;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ZoneManager {

    private final boolean isFixed;

    private final ScreenCapture screenCapture;

    private Map<String, Rectangle> minimap;
    private Map<String, Rectangle> ctrlPanel;
    private Map<String, Rectangle> chatTabs;
    private List<Rectangle> inventorySlots;

    private final String[] zoneTemplates = {
            "src/images/UI/minimap.png",
            "src/images/UI/inv.png",
            "src/images/UI/chat.png",
            "src/images/UI/minimap_fixed.png"
    };

    private final double[] zoneThresholds = {
            0.02,
            0.035,
            0.032,
            0.018
    };

    public ZoneManager( ScreenCapture screenCapture, Boolean isFixed) throws Exception {
        this.isFixed = isFixed;
        this.screenCapture = screenCapture;
        mapper();
    }

    public void mapper() throws Exception {
        try {
            chatTabs = SubZoneMapper.mapChat(locateUIElement(Path.of(zoneTemplates[2]), zoneThresholds[2]));
            ctrlPanel = SubZoneMapper.mapCtrlPanel(locateUIElement(Path.of(zoneTemplates[1]), zoneThresholds[1]));
            inventorySlots = SubZoneMapper.mapInventory(locateUIElement(Path.of(zoneTemplates[1]), zoneThresholds[1]));

            if (isFixed) {
                minimap = SubZoneMapper.mapFixedMinimap(locateUIElement(Path.of(zoneTemplates[3]), zoneThresholds[3]));
            } else {
                minimap = SubZoneMapper.mapMinimap(locateUIElement(Path.of(zoneTemplates[0]), zoneThresholds[0]));
            }
        } catch (Exception e) {
            System.err.println("[ZoneManager] Mapping failed: " + e.getMessage());
        }
    }

    public BufferedImage getGameView() throws Exception {
        BufferedImage gameView;
        if (isFixed) {
            gameView = screenCapture.captureZone(SubZoneMapper.mapFixedGameView(screenCapture.getWindowBounds(WindowHandler.getTargetWindow())));
        } else {
            BufferedImage gameViewMask = screenImage();
            for (int i = 0; i < 3; i++) {
                Rectangle element = locateUIElement(Path.of(zoneTemplates[i]), zoneThresholds[i]);
                gameViewMask = MaskZones.maskZones(gameViewMask, element);
            }
            gameView = gameViewMask;
        }
        DisplayImage.display(gameView);
        return gameView;
    }

    public Rectangle locateUIElement(Path templatePath, double threshold) throws Exception {
        BufferedImage template = ImageIO.read(templatePath.toFile());
        return TemplateMatching.patternMatch(template, screenImage(), threshold, false);
    }

    public BufferedImage screenImage() throws AWTException {
        return screenCapture.captureWindow(WindowHandler.getTargetWindow());
    }

    public Map<String, Rectangle> getMinimap() { return minimap; }
    public Map<String, Rectangle> getCtrlPanel() { return ctrlPanel; }
    public Map<String, Rectangle> getChatTabs() { return chatTabs; }
    public List<Rectangle> getInventorySlots() { return inventorySlots; }
}
