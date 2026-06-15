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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Edit-mode only: draws outlines around managed docks and a highlight on the dock the drag is
 * currently hovering. Draws nothing when locked.
 */
class Rs3InterfaceOverlay extends Overlay
{
	private static final Color OUTLINE_COLOR = new Color(255, 255, 255, 140);
	private static final Color DROP_HIGHLIGHT_COLOR = new Color(0, 255, 0, 80);
	private static final Color DRAG_COLOR = new Color(0, 200, 255, 200);
	private static final Stroke OUTLINE_STROKE = new BasicStroke(1f);
	private static final Stroke DRAG_STROKE = new BasicStroke(2f);

	private final Rs3InterfacePlugin plugin;

	@Inject
	Rs3InterfaceOverlay(Rs3InterfacePlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isEditMode())
		{
			return null;
		}

		// Outline every managed dock.
		for (Rs3InterfacePlugin.DockBounds db : plugin.getDockBounds())
		{
			Rectangle r = db.getBounds();
			boolean isDropTarget = db == plugin.getHoveredDock();
			if (isDropTarget)
			{
				graphics.setColor(DROP_HIGHLIGHT_COLOR);
				graphics.fillRect(r.x, r.y, r.width, r.height);
			}
			graphics.setColor(OUTLINE_COLOR);
			graphics.setStroke(OUTLINE_STROKE);
			graphics.drawRect(r.x, r.y, r.width, r.height);
		}

		// Outline the panel currently being dragged at its live position.
		Rectangle drag = plugin.getDragRect();
		if (drag != null)
		{
			graphics.setColor(DRAG_COLOR);
			graphics.setStroke(DRAG_STROKE);
			graphics.drawRect(drag.x, drag.y, drag.width, drag.height);
		}

		return null;
	}
}
