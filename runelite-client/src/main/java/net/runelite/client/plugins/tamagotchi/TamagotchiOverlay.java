package net.runelite.client.plugins.tamagotchi;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
class TamagotchiOverlay extends Overlay implements KeyListener, MouseListener
{
	private static final int LCD_WIDTH = 32;
	private static final int LCD_HEIGHT = 16;
	private static final int ICON_RENDER_SIZE = 18;
	// Decoration center-X positions in bg.png (330px wide) — measured from the actual image
	private static final int[] BG_TOP_CX = {50, 125, 199, 272};
	private static final int[] BG_BOT_CX = {57, 122, 189, 268};
	private static final int BG_WIDTH = 330;

	private static final Color LCD_FG = new Color(30, 30, 30);
	private static final Color LCD_OFF = new Color(195, 200, 185, 100);
	private static final Color LCD_BG = new Color(230, 225, 218, 180);
	private static final Color SHELL_BG = new Color(250, 245, 230);
	private static final Color SHELL_BORDER = new Color(200, 195, 180);

	private final TamagotchiPlugin plugin;
	private final TamagotchiConfig config;

	private final boolean[] btnPressed = new boolean[3];
	private final Rectangle[] iconRects = new Rectangle[8];
	private Rectangle lcdRect = new Rectangle();
	private BufferedImage bgImage;
	private boolean bgLoaded;
	private BufferedImage[] iconImagesOn;
	private BufferedImage[] iconImagesOff;
	private int cachedTotalW, cachedTotalH;

	@Inject
	TamagotchiOverlay(TamagotchiPlugin plugin, TamagotchiConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		for (int i = 0; i < 8; i++)
		{
			iconRects[i] = new Rectangle();
		}
	}

	private void loadAssets()
	{
		if (bgLoaded)
		{
			return;
		}
		bgLoaded = true;
		try
		{
			bgImage = ImageIO.read(TamagotchiOverlay.class.getResourceAsStream("bg.png"));
		}
		catch (Exception e)
		{
			log.warn("Could not load tamagotchi background", e);
		}
		// Pre-render icon images from FontAwesome SVG paths
		iconImagesOn = TamaIcons.createIcons(ICON_RENDER_SIZE, new Color(20, 20, 20, 230));
		iconImagesOff = TamaIcons.createIcons(ICON_RENDER_SIZE, new Color(255, 255, 255, 200));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		loadAssets();
		int ps = config.pixelSize();
		int lcdW = LCD_WIDTH * ps;
		int lcdH = LCD_HEIGHT * ps;

		int topPad = 5;
		int botPad = 0;
		int iconRowH = ICON_RENDER_SIZE + 8;
		int topLcdGap = 1;
		int botLcdGap = 5;
		int totalW = lcdW + 24;
		int totalH = topPad + iconRowH + topLcdGap + lcdH + botLcdGap + iconRowH + botPad;

		cachedTotalW = totalW;
		cachedTotalH = totalH;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Shell
		g.setColor(SHELL_BG);
		g.fillRoundRect(0, 0, totalW, totalH, 14, 14);
		g.setColor(SHELL_BORDER);
		g.setStroke(new BasicStroke(1.5f));
		g.drawRoundRect(1, 1, totalW - 2, totalH - 2, 14, 14);

		// Background image at 30% opacity — drawn in 3 slices to align
		// decorative strips with icon rows. bg.png is 330x312:
		// top strip (hearts/flowers): y 0-60, checkerboard: y 60-252, bottom strip: y 252-312
		if (bgImage != null)
		{
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
			int topRowEnd = topPad + iconRowH;
			int botRowStart = topPad + iconRowH + topLcdGap + lcdH + botLcdGap;
			// Top strip → top icon row
			g.drawImage(bgImage, 0, 0, totalW, topRowEnd,
				0, 0, 330, 60, null);
			// Checkerboard → LCD area
			g.drawImage(bgImage, 0, topRowEnd, totalW, botRowStart,
				0, 60, 330, 252, null);
			// Bottom strip → bottom icon row (trimmed to 295 to exclude empty border)
			g.drawImage(bgImage, 0, botRowStart, totalW, totalH,
				0, 252, 330, 295, null);
			g.setComposite(old);
		}

		if (!plugin.isRunning())
		{
			g.setColor(new Color(80, 80, 80));
			g.setFont(new Font("SansSerif", Font.PLAIN, 10));
			String msg = config.romPath().isEmpty() ? "Set ROM path in config" : "Loading ROM...";
			FontMetrics fm = g.getFontMetrics();
			g.drawString(msg, (totalW - fm.stringWidth(msg)) / 2, totalH / 2);
			return new Dimension(totalW, totalH);
		}

		boolean[] icons = plugin.getIcons();
		int lcdX = (totalW - lcdW) / 2;

		// --- Top icons (0-3) --- aligned to bg.png decoration positions
		int topY = topPad + iconRowH - ICON_RENDER_SIZE - 4;
		for (int i = 0; i < 4; i++)
		{
			int ix = BG_TOP_CX[i] * totalW / BG_WIDTH - ICON_RENDER_SIZE / 2 + 1;
			iconRects[i].setBounds(ix, topY, ICON_RENDER_SIZE, ICON_RENDER_SIZE);
			BufferedImage img = icons[i] ? iconImagesOn[i] : iconImagesOff[i];
			if (img != null)
			{
				g.drawImage(img, ix, topY, null);
			}
		}

		// --- LCD ---
		int lcdY = topPad + iconRowH + topLcdGap;
		lcdRect.setBounds(lcdX - 3, lcdY - 3, lcdW + 6, lcdH + 6);
		g.setColor(LCD_BG);
		g.fillRoundRect(lcdX - 3, lcdY - 3, lcdW + 6, lcdH + 6, 6, 6);

		boolean[][] matrix = plugin.getMatrix();
		for (int row = 0; row < LCD_HEIGHT; row++)
		{
			for (int col = 0; col < LCD_WIDTH; col++)
			{
				g.setColor(matrix[row][col] ? LCD_FG : LCD_OFF);
				g.fillRect(lcdX + col * ps, lcdY + row * ps, ps - 1, ps - 1);
			}
		}

		// --- Bottom icons (4-7) --- aligned to bg.png decoration positions
		int botY = lcdY + lcdH + botLcdGap - 3;
		for (int i = 0; i < 4; i++)
		{
			int ix = BG_BOT_CX[i] * totalW / BG_WIDTH - ICON_RENDER_SIZE / 2;
			iconRects[4 + i].setBounds(ix, botY, ICON_RENDER_SIZE, ICON_RENDER_SIZE);
			BufferedImage img = icons[4 + i] ? iconImagesOn[4 + i] : iconImagesOff[4 + i];
			if (img != null)
			{
				g.drawImage(img, ix, botY, null);
			}
		}

		return new Dimension(totalW, totalH);
	}

	private boolean isInOverlay(MouseEvent e)
	{
		Rectangle bounds = getBounds();
		if (bounds.width == 0)
		{
			return false;
		}
		int mx = e.getX() - bounds.x;
		int my = e.getY() - bounds.y;
		return mx >= 0 && my >= 0 && mx < cachedTotalW && my < cachedTotalH;
	}

	// -- KeyListener --

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		int btn = keyToButton(e);
		if (btn >= 0)
		{
			plugin.pressButton(btn, true);
			btnPressed[btn] = true;
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		int btn = keyToButton(e);
		if (btn >= 0)
		{
			plugin.pressButton(btn, false);
			btnPressed[btn] = false;
		}
	}

	private int keyToButton(KeyEvent e)
	{
		String key = String.valueOf(e.getKeyChar());
		if (key.equalsIgnoreCase(config.keyLeft())) return 0;
		if (key.equalsIgnoreCase(config.keyMiddle())) return 1;
		if (key.equalsIgnoreCase(config.keyRight())) return 2;
		return -1;
	}

	// -- MouseListener --

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (!isInOverlay(e) || !plugin.isRunning())
		{
			return e;
		}

		Rectangle bounds = getBounds();
		int mx = e.getX() - bounds.x;
		int my = e.getY() - bounds.y;

		if (SwingUtilities.isRightMouseButton(e))
		{
			plugin.pressButton(TamaCpu.BTN_RIGHT, true);
			plugin.pressButton(TamaCpu.BTN_RIGHT, false);
			e.consume();
			return e;
		}

		if (lcdRect.contains(mx, my))
		{
			plugin.pressButton(TamaCpu.BTN_MIDDLE, true);
			plugin.pressButton(TamaCpu.BTN_MIDDLE, false);
			e.consume();
			return e;
		}

		for (int i = 0; i < 8; i++)
		{
			if (iconRects[i].contains(mx, my))
			{
				plugin.pressButton(TamaCpu.BTN_LEFT, true);
				plugin.pressButton(TamaCpu.BTN_LEFT, false);
				e.consume();
				return e;
			}
		}

		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		return e;
	}
}
