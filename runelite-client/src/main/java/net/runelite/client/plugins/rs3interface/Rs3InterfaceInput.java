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

import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

/**
 * Edit-mode drag/drop handling. When editing, all events are consumed so dragging panels never
 * clicks game items. When locked, events pass through untouched (vanilla interaction).
 */
class Rs3InterfaceInput extends MouseAdapter
{
	private final Rs3InterfacePlugin plugin;

	Rs3InterfaceInput(Rs3InterfacePlugin plugin)
	{
		this.plugin = plugin;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!plugin.isEditMode() || !SwingUtilities.isLeftMouseButton(event))
		{
			return event;
		}

		if (plugin.startDrag(event.getX(), event.getY()))
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		if (!plugin.isEditMode() || !plugin.isDragging())
		{
			return event;
		}

		plugin.updateDrag(event.getX(), event.getY());
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if (!plugin.isEditMode() || !plugin.isDragging())
		{
			return event;
		}

		plugin.endDrag(event.getX(), event.getY());
		event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		// Swallow the click that follows a press/release in edit mode so it never reaches the game.
		if (plugin.isEditMode())
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		if (plugin.isEditMode())
		{
			plugin.updateHover(event.getX(), event.getY());
		}
		return event;
	}
}
