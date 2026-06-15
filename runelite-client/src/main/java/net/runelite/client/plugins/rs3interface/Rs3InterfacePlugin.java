/*
 * Copyright (c) 2026, RuneLite contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.rs3interface;

import com.google.inject.Provides;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ResizeableChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

@PluginDescriptor(
	name = "RS3 Interface",
	description = "Keep multiple OSRS side-panel tabs open at once, repositioned and combined into docks",
	tags = {"interface", "panels", "tabs", "dock", "rs3", "layout"},
	enabledByDefault = false
)
@Slf4j
public class Rs3InterfacePlugin extends Plugin
{
	/** Gap in pixels between a dock's content container and its stone strip below it. */
	private static final int STRIP_GAP = 2;
	/** Gap in pixels between stones in a strip. */
	private static final int STONE_GAP = 1;
	/** Minimum threshold to count as a drag rather than a click. */
	private static final int DRAG_THRESHOLD = 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Rs3InterfaceConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Rs3InterfaceOverlay overlay;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LayoutManager layoutManager;

	private Rs3InterfaceInput input;

	private final HotkeyListener editModeHotkey = new HotkeyListener(() -> config.editModeHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			toggleEditMode();
		}
	};

	@Getter
	private boolean editMode;

	// Live bounds of each dock, refreshed each tick, used by the overlay and hit-testing.
	@Getter
	private final List<DockBounds> dockBounds = new ArrayList<>();

	// Drag state (edit mode).
	private ManagedPanel dragPanel;
	private int dragStartX;
	private int dragStartY;
	private int dragOffsetX;
	private int dragOffsetY;
	private int dragX;
	private int dragY;
	private boolean dragMoved;
	@Getter
	@Nullable
	private DockBounds hoveredDock;

	private boolean warnedFixedMode;

	@Provides
	Rs3InterfaceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(Rs3InterfaceConfig.class);
	}

	@Override
	protected void startUp()
	{
		input = new Rs3InterfaceInput(this);
		overlayManager.add(overlay);
		keyManager.registerKeyListener(editModeHotkey);
		mouseManager.registerMouseListener(input);
		clientThread.invokeLater(layoutManager::load);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		keyManager.unregisterKeyListener(editModeHotkey);
		mouseManager.unregisterMouseListener(input);
		editMode = false;
		dragPanel = null;
		hoveredDock = null;
		clientThread.invokeLater(this::clearAllForced);
		warnedFixedMode = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			warnedFixedMode = false;
		}
	}

	@Subscribe
	public void onResizeableChanged(ResizeableChanged e)
	{
		// On a layout-mode change the game rebuilds the interface; drop forced state so vanilla
		// widgets aren't stranded, then let the per-tick loop re-assert if still resizable.
		clearAllForced();
		warnedFixedMode = false;
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged e)
	{
		if (e.getIndex() != VarClientID.TOPLEVEL_PANEL)
		{
			return;
		}

		// The game just changed the active tab (a stone was clicked). If that tab is a managed
		// panel, make it the active member of its dock so its content shows above the strip.
		int tabIndex = client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
		ManagedPanel panel = ManagedPanel.forTabIndex(tabIndex);
		if (panel != null && layoutManager.isManaged(panel))
		{
			layoutManager.setActive(panel);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick e)
	{
		dockBounds.clear();

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (!client.isResized())
		{
			// Fixed mode: stay vanilla, warn once.
			if (!warnedFixedMode && !layoutManager.getDocks().isEmpty())
			{
				log.info("RS3 Interface only works in resizable mode; staying vanilla in fixed mode.");
				warnedFixedMode = true;
			}
			return;
		}

		enforceLayout();
	}

	/**
	 * Per-tick layout algorithm: for each dock, lay out the stone strip, show the active member's
	 * content above the strip, and hide the other members' content. Re-asserted every tick to win
	 * against the game's clientscripts which reset position/visibility on tab switches.
	 */
	private void enforceLayout()
	{
		for (Dock dock : layoutManager.getDocks())
		{
			List<ManagedPanel> members = enabledMembers(dock);
			if (members.isEmpty())
			{
				continue;
			}

			ManagedPanel active = dock.activePanel();
			if (active == null || !members.contains(active))
			{
				active = members.get(0);
			}

			Widget content = client.getWidget(active.getContainerComponentId());
			if (content == null)
			{
				continue;
			}

			int dockW = content.getWidth();
			int dockH = content.getHeight();

			// 1) Position the content container at the dock origin.
			setForcedCanvasPosition(content, dock.getX(), dock.getY());
			content.setHidden(false);
			content.revalidate();
			content.revalidateScroll();

			// 2) Lay out the stone strip along the bottom edge.
			int stripY = dock.getY() + dockH + STRIP_GAP;
			int stoneX = dock.getX();
			int stripHeight = 0;
			for (ManagedPanel member : members)
			{
				Widget stone = client.getWidget(member.getStoneComponentId());
				if (stone == null)
				{
					continue;
				}
				setForcedCanvasPosition(stone, stoneX, stripY);
				stone.setHidden(false);
				stone.revalidate();
				stoneX += stone.getWidth() + STONE_GAP;
				stripHeight = Math.max(stripHeight, stone.getHeight());
			}

			// 3) Hide the non-active members' content containers.
			for (ManagedPanel member : members)
			{
				if (member == active)
				{
					continue;
				}
				Widget mc = client.getWidget(member.getContainerComponentId());
				if (mc != null)
				{
					mc.setHidden(true);
				}
			}

			int totalH = dockH + STRIP_GAP + stripHeight;
			int totalW = Math.max(dockW, stoneX - dock.getX());
			dockBounds.add(new DockBounds(dock, new Rectangle(dock.getX(), dock.getY(), totalW, totalH)));
		}

		clampDocksToViewport();
	}

	private List<ManagedPanel> enabledMembers(Dock dock)
	{
		List<ManagedPanel> out = new ArrayList<>();
		for (ManagedPanel panel : dock.memberPanels())
		{
			if (panel.isEnabled(config))
			{
				out.add(panel);
			}
		}
		return out;
	}

	private void clampDocksToViewport()
	{
		int w = client.getCanvasWidth();
		int h = client.getCanvasHeight();
		for (DockBounds db : dockBounds)
		{
			Rectangle r = db.getBounds();
			int nx = LayoutManager.clamp(db.getDock().getX(), r.width, w);
			int ny = LayoutManager.clamp(db.getDock().getY(), r.height, h);
			db.getDock().setX(nx);
			db.getDock().setY(ny);
		}
	}

	/**
	 * Positions a widget at the given canvas coordinates by converting to parent-relative
	 * coordinates (setForcedPosition is relative to the widget's parent).
	 */
	private void setForcedCanvasPosition(Widget widget, int canvasX, int canvasY)
	{
		int parentX = 0;
		int parentY = 0;
		Widget parent = widget.getParent();
		if (parent != null)
		{
			Point pl = parent.getCanvasLocation();
			if (pl != null)
			{
				parentX = pl.getX();
				parentY = pl.getY();
			}
		}
		widget.setForcedPosition(canvasX - parentX, canvasY - parentY);
	}

	private void clearAllForced()
	{
		for (ManagedPanel panel : ManagedPanel.values())
		{
			Widget content = client.getWidget(panel.getContainerComponentId());
			if (content != null)
			{
				content.setForcedPosition(-1, -1);
				content.revalidate();
			}
			Widget stone = client.getWidget(panel.getStoneComponentId());
			if (stone != null)
			{
				stone.setForcedPosition(-1, -1);
				stone.revalidate();
			}
		}
	}

	void toggleEditMode()
	{
		editMode = !editMode;
		if (!editMode)
		{
			dragPanel = null;
			hoveredDock = null;
			layoutManager.save();
		}
		log.debug("RS3 Interface edit mode: {}", editMode);
	}

	// --- drag/drop (called from Rs3InterfaceInput on the AWT thread) --------

	boolean isDragging()
	{
		return dragPanel != null;
	}

	@Nullable
	Rectangle getDragRect()
	{
		if (dragPanel == null || !dragMoved)
		{
			return null;
		}
		DockBounds db = boundsFor(dragPanel);
		int w = db != null ? db.getBounds().width : 30;
		int h = db != null ? db.getBounds().height : 30;
		return new Rectangle(dragX - dragOffsetX, dragY - dragOffsetY, w, h);
	}

	/**
	 * Starts dragging the dock/panel under (x, y). If nothing managed is under the cursor, tries
	 * to grab the vanilla side container so an undocked tab can be pulled into a new dock.
	 */
	boolean startDrag(int x, int y)
	{
		DockBounds db = dockAt(x, y);
		if (db != null)
		{
			dragPanel = db.getDock().activePanel();
			dragOffsetX = x - db.getBounds().x;
			dragOffsetY = y - db.getBounds().y;
		}
		else
		{
			// Try to grab an unmanaged, currently-visible panel (the vanilla active tab).
			ManagedPanel panel = visibleUnmanagedPanelAt(x, y);
			if (panel == null)
			{
				return false;
			}
			dragPanel = panel;
			Widget content = client.getWidget(panel.getContainerComponentId());
			Point pl = content == null ? null : content.getCanvasLocation();
			dragOffsetX = pl == null ? 0 : x - pl.getX();
			dragOffsetY = pl == null ? 0 : y - pl.getY();
		}

		dragStartX = x;
		dragStartY = y;
		dragX = x;
		dragY = y;
		dragMoved = false;
		return true;
	}

	void updateDrag(int x, int y)
	{
		dragX = x;
		dragY = y;
		if (Math.abs(x - dragStartX) > DRAG_THRESHOLD || Math.abs(y - dragStartY) > DRAG_THRESHOLD)
		{
			dragMoved = true;
		}
		hoveredDock = dockAt(x, y);
	}

	void endDrag(int x, int y)
	{
		if (dragPanel == null)
		{
			return;
		}

		final ManagedPanel panel = dragPanel;
		dragPanel = null;
		hoveredDock = null;

		if (!dragMoved)
		{
			// Treat as no-op (a plain click in edit mode is swallowed elsewhere).
			return;
		}

		final int dropX = snap(Math.max(0, x - dragOffsetX));
		final int dropY = snap(Math.max(0, y - dragOffsetY));
		final DockBounds target = dockAt(x, y);

		clientThread.invokeLater(() ->
		{
			if (target != null && !target.getDock().contains(panel))
			{
				// dropped onto another dock: join its strip and become the active member
				layoutManager.merge(panel, target.getDock());
			}
			else if (target != null)
			{
				// dropped onto its own dock: just move that dock
				layoutManager.move(target.getDock(), dropX, dropY);
			}
			else
			{
				// dropped on empty space: split into its own dock
				layoutManager.splitOut(panel, dropX, dropY);
			}
			layoutManager.save();
		});
	}

	private int snap(int v)
	{
		if (!config.snapToGrid())
		{
			return v;
		}
		int g = Math.max(1, config.gridSize());
		return Math.round((float) v / g) * g;
	}

	void updateHover(int x, int y)
	{
		hoveredDock = dockAt(x, y);
	}

	@Nullable
	private DockBounds dockAt(int x, int y)
	{
		for (DockBounds db : dockBounds)
		{
			if (db.getBounds().contains(x, y))
			{
				return db;
			}
		}
		return null;
	}

	@Nullable
	private DockBounds boundsFor(ManagedPanel panel)
	{
		for (DockBounds db : dockBounds)
		{
			if (db.getDock().contains(panel))
			{
				return db;
			}
		}
		return null;
	}

	@Nullable
	private ManagedPanel visibleUnmanagedPanelAt(int x, int y)
	{
		for (ManagedPanel panel : ManagedPanel.values())
		{
			if (!panel.isEnabled(config) || layoutManager.isManaged(panel))
			{
				continue;
			}
			Widget content = client.getWidget(panel.getContainerComponentId());
			if (content == null || content.isHidden())
			{
				continue;
			}
			Rectangle b = content.getBounds();
			if (b != null && b.contains(x, y))
			{
				return panel;
			}
		}
		return null;
	}

	/**
	 * Live screen bounds of a dock (content + strip), refreshed each tick. Used by the overlay and
	 * for hit-testing in edit mode.
	 */
	@Getter
	static final class DockBounds
	{
		private final Dock dock;
		private final Rectangle bounds;

		DockBounds(Dock dock, Rectangle bounds)
		{
			this.dock = dock;
			this.bounds = bounds;
		}
	}
}
