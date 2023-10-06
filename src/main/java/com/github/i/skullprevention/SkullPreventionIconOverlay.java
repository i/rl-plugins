package com.github.i.skullprevention;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;

import static com.github.i.skullprevention.SkullPreventionIconConfig.Mode.DISABLED_ONLY;
import static com.github.i.skullprevention.SkullPreventionIconConfig.Mode.ENABLED_ONLY;

class SkullPreventionIconOverlay extends OverlayPanel {

	@Inject
	private SkullPreventionIconPlugin plugin;
	@Inject
	private SkullPreventionIconConfig config;

	@Inject
	private SkullPreventionIconOverlay() {
		this.setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		this.setResizable(false);
		this.setMovable(true);
		this.setSnappable(true);
	}

	private BufferedImage redSkull = null;
	private BufferedImage normalSkull = null;

	@Override
	public Dimension render(Graphics2D graphics) {
		if (config.pvpOnly() && !plugin.isInPVP()) {
			return null;
		}

		if (config.mode() == DISABLED_ONLY && plugin.skullPreventionEnabled()) {
			return null;
		}

		if (config.mode() == ENABLED_ONLY && !plugin.skullPreventionEnabled()) {
			return null;
		}

		if (redSkull == null || normalSkull == null) {
			reloadImages(config.size());
		}

		var skullPreventionOn = plugin.skullPreventionEnabled();

		panelComponent.setBackgroundColor(ColorUtil.colorWithAlpha(Color.BLACK, 0));
		panelComponent.setWrap(false);

		var lineComponentBuilder = LineComponent
							.builder()
							.left("SKULL PREVENTION")
							.leftColor(Color.WHITE);

		if (skullPreventionOn) {
			lineComponentBuilder .right("ON").rightColor(Color.GREEN);
			panelComponent.getChildren().add(new ImageComponent(normalSkull));
		} else {
			lineComponentBuilder.right("OFF").rightColor(Color.RED);
			panelComponent.getChildren().add(new ImageComponent(redSkull));
		}

		panelComponent.getChildren().add(lineComponentBuilder.build());
		return super.render(graphics);
	}


	protected void reloadImages(int size) {
		redSkull = loadSkullImage(true, size);
		normalSkull = loadSkullImage(false, size);
	}

	// image is a square, so we only need one size height and width
	private BufferedImage loadSkullImage(boolean redEyes, int size) {
		var path = "/skull" + (redEyes ? "-red" : "") + ".png";
		var img =  ImageUtil.loadImageResource(SkullPreventionIconPlugin.class, path);
		return ImageUtil.resizeImage(img, size, size, true);
	}
}