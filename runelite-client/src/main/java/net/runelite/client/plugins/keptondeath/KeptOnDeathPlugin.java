/*
 * Copyright (c) 2018, TheStonedTurtle <https://github.com/TheStonedTurtle>
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
package net.runelite.client.plugins.keptondeath;

import com.google.common.eventbus.Subscribe;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.SkullIcon;
import net.runelite.api.Varbits;
import net.runelite.api.WidgetType;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Kept on Death",
	description = "Reworks the Items Kept on Death interface to be more accurate"
)
@Slf4j
public class KeptOnDeathPlugin extends Plugin
{
	private static final int SCRIPT_ID = 1603;

	private static final int MAX_ROW_ITEMS = 8;
	private static final int STARTING_X = 5;
	private static final int STARTING_Y = 25;
	private static final int X_INCREMENT = 40;
	private static final int Y_INCREMENT = 38;
	private static final int ORIGINAL_WIDTH = 36;
	private static final int ORIGINAL_HEIGHT = 32;

	private static final String MAX_KEPT_ITEMS_FORMAT = "<col=ffcc33>Max items kept on death :<br><br><col=ffcc33>~ %s ~";
	private static final String ACTION_TEXT = "Item: <col=ff981f>%s";
	private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,###");

	private static final String NORMAL = "Protecting %s items by default";
	private static final String IS_SKULLED = "<col=ff3333>PK skull<col=ff981f> -3";
	private static final String PROTECTING_ITEM = "<col=ff3333>Protect Item prayer<col=ff981f> +1";
	private static final String WHITE_OUTLINE = "Items with a <col=ffffff>white outline<col=ff981f> will always be lost.";
	private static final String HAS_BOND = "<col=00ff00>Bonds</col> are always protected, so are not shown here.";
	private static final String CHANGED_MECHANICS = "Untradeable items are kept on death in non-pvp scenarios.";
	private static final String NON_PVP = "If you die you have 1 hour to retrieve your lost items.";
	private static final String LINE_BREAK = "<br>";

	private boolean isSkulled = false;
	private boolean protectingItem = false;
	private boolean hasAlwaysLost = false;
	private boolean hasBond = false;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Getter
	private boolean widgetVisible = false;

	@Subscribe
	protected void onGameTick(GameTick t)
	{
		boolean old = widgetVisible;
		widgetVisible = client.getWidget(WidgetInfo.ITEMS_LOST_ON_DEATH_CONTAINER) != null;
		if (widgetVisible && !old)
		{
			// Above 20 wildy we have nothing new to display
			if (getCurrentWildyLevel() <= 20)
			{
				recreateItemsKeptOnDeathWidget();
				updateKeptWidgetInfoText();
			}
		}
	}

	private int getCurrentWildyLevel()
	{
		if (client.getVar(Varbits.IN_WILDERNESS) != 1)
		{
			return -1;
		}

		int y = client.getLocalPlayer().getWorldLocation().getY();

		int underLevel = ((y - 9920) / 8) + 1;
		int upperLevel = ((y - 3520) / 8) + 1;
		return (y > 6400 ? underLevel : upperLevel);
	}

	private int getDefaultItemsKept()
	{
		isSkulled = false;
		protectingItem = false;
		int count = 3;

		SkullIcon s = client.getLocalPlayer().getSkullIcon();
		if (s != null && s.equals(SkullIcon.SKULL))
		{
			count = 0;
			isSkulled = true;
		}

		if (client.getVar(Varbits.PRAYER_PROTECT_ITEM) == 1)
		{
			count += 1;
			protectingItem = true;
		}

		return count;
	}

	private void recreateItemsKeptOnDeathWidget()
	{
		isSkulled = false;
		protectingItem = false;
		hasAlwaysLost = false;
		hasBond = false;

		Widget lost = client.getWidget(WidgetInfo.ITEMS_LOST_ON_DEATH_CONTAINER);
		Widget kept = client.getWidget(WidgetInfo.ITEMS_KEPT_ON_DEATH_CONTAINER);
		if (lost != null && kept != null)
		{
			// Grab all items on player and sort by price.
			List<Item> items = new ArrayList<>();
			ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
			Item[] inv = inventory == null ? new Item[0] : inventory.getItems();
			ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
			Item[] equip = equipment == null ? new Item[0] : equipment.getItems();
			Collections.addAll(items, inv);
			Collections.addAll(items, equip);
			// Sort by item price
			items.sort(new Comparator<Item>()
			{
				@Override
				public int compare(Item o1, Item o2)
				{
					return itemManager.getItemPrice(o2.getId()) - itemManager.getItemPrice(o1.getId());
				}
			});

			int keepCount = getDefaultItemsKept();
			int wildyLevel = getCurrentWildyLevel();

			List<Widget> keptItems = new ArrayList<>();
			List<Widget> lostItems = new ArrayList<>();
			for (Item i : items)
			{
				int id = i.getId();
				if (id == -1)
				{
					continue;
				}

				if (id == ItemID.OLD_SCHOOL_BOND || id == ItemID.OLD_SCHOOL_BOND_UNTRADEABLE)
				{
					hasBond = true;
					continue;
				}

				Widget itemWidget = client.createWidget();
				itemWidget.setType(WidgetType.GRAPHIC);
				itemWidget.setItemId(i.getId());
				itemWidget.setItemQuantity(i.getQuantity());
				itemWidget.setHasListener(true);
				itemWidget.setIsIf3(true);
				itemWidget.setOriginalWidth(ORIGINAL_WIDTH);
				itemWidget.setOriginalHeight(ORIGINAL_HEIGHT);
				itemWidget.setBorderType(1);

				ItemComposition c = itemManager.getItemComposition(i.getId());
				itemWidget.setAction(1, String.format(ACTION_TEXT, c.getName()));
				if (keepCount > 0 || !checkTradeable(i.getId(), c))
				{
					keepCount = keepCount < 1 ? 0 : keepCount - 1;

					// Certain items are turned into broken variants inside the wilderness.
					if (BrokenOnDeathItem.check(i.getId()))
					{
						itemWidget.setOnOpListener(SCRIPT_ID, 1, i.getQuantity(), c.getName());
						keptItems.add(itemWidget);
						continue;
					}

					// Ignore all non tradeables in wildy except for the above case(s).
					if (wildyLevel > 0)
					{
						itemWidget.setOnOpListener(SCRIPT_ID, 0, i.getQuantity(), c.getName());
						lostItems.add(itemWidget);
						continue;
					}

					// Certain items are always lost on death and have a white outline
					AlwaysLostItem item = AlwaysLostItem.getByItemID(i.getId());
					if (item != null)
					{
						// Some of these items are actually lost on death, like the looting bag
						if (!item.isKept())
						{
							itemWidget.setOnOpListener(SCRIPT_ID, 0, i.getQuantity(), c.getName());
							itemWidget.setBorderType(2);
							lostItems.add(itemWidget);
							hasAlwaysLost = true;
							continue;
						}
					}

					itemWidget.setOnOpListener(SCRIPT_ID, 1, i.getQuantity(), c.getName());
					keptItems.add(itemWidget);
				}
				else
				{
					itemWidget.setOnOpListener(SCRIPT_ID, 0, i.getQuantity(), c.getName());
					lostItems.add(itemWidget);
				}
			}

			setWidgetChildren(kept, keptItems);
			if (keptItems.size() > 8)
			{
				// Adjust items lost container position if new rows were added to kept items container
				lost.setOriginalY(lost.getOriginalY() + ((keptItems.size() / 8) * Y_INCREMENT));
				lost.setOriginalHeight(lost.getOriginalHeight() - ((keptItems.size() / 8) * Y_INCREMENT));
			}
			setWidgetChildren(lost, lostItems);
		}
	}

	/**
	 * Wrapper for widget.setChildren() but updates the child index and original positions
	 * @param parent Widget to override children
	 * @param widgets Children to set on parent
	 */
	private void setWidgetChildren(Widget parent, List<Widget> widgets)
	{
		Widget[] children = parent.getChildren();
		if (children == null)
		{
			// Create a child so we can copy the returned Widget[] and avoid hn casting issues from creating a new Widget[]
			parent.createChild(0, WidgetType.GRAPHIC);
			children = parent.getChildren();
		}
		Widget[] itemsArray = Arrays.copyOf(children, widgets.size());

		int parentId = parent.getId();
		int startingIndex = 0;
		for (Widget w : widgets)
		{
			int originalX = STARTING_X + ((startingIndex % MAX_ROW_ITEMS) * X_INCREMENT);
			int originalY = STARTING_Y + ((startingIndex / MAX_ROW_ITEMS) * Y_INCREMENT);

			w.setParentId(parentId);
			w.setId(parentId);
			w.setIndex(startingIndex);

			w.setOriginalX(originalX);
			w.setOriginalY(originalY);
			w.revalidate();

			itemsArray[startingIndex] = w;
			startingIndex++;
		}

		parent.setChildren(itemsArray);
		parent.revalidate();
	}

	/**
	 * Corrects the Information panel with custom text
	 */
	private void updateKeptWidgetInfoText()
	{
		String textToAdd = String.format(NORMAL, getDefaultItemsKept());

		if (isSkulled)
		{
			textToAdd += LINE_BREAK + IS_SKULLED;
		}

		if (protectingItem)
		{
			textToAdd += LINE_BREAK + PROTECTING_ITEM;
		}

		if (getCurrentWildyLevel() < 1)
		{
			textToAdd += LINE_BREAK + LINE_BREAK + NON_PVP;
		}

		if (hasAlwaysLost)
		{
			textToAdd += LINE_BREAK + LINE_BREAK + WHITE_OUTLINE;
		}

		if (hasBond)
		{
			textToAdd += LINE_BREAK + LINE_BREAK + HAS_BOND;
		}

		textToAdd += LINE_BREAK + LINE_BREAK + CHANGED_MECHANICS;

		Widget info = client.getWidget(WidgetInfo.ITEMS_KEPT_INFORMATION_CONTAINER);
		info.setText(textToAdd);

		// Update Items lost total value
		Widget lost = client.getWidget(WidgetInfo.ITEMS_LOST_ON_DEATH_CONTAINER);
		int total = 0;
		for (Widget w : lost.getChildren())
		{
			if (w.getItemId() == -1)
			{
				continue;
			}
			total += itemManager.getItemPrice(w.getItemId());
		}
		Widget lostValue = client.getWidget(WidgetInfo.ITEMS_LOST_VALUE);
		lostValue.setText(NUMBER_FORMAT.format(total) + " gp");

		// Update Max items kept
		Widget kept = client.getWidget(WidgetInfo.ITEMS_KEPT_ON_DEATH_CONTAINER);
		Widget max = client.getWidget(WidgetInfo.ITEMS_KEPT_MAX);
		max.setText(String.format(MAX_KEPT_ITEMS_FORMAT, kept.getChildren().length));
	}

	private boolean checkTradeable(int id, ItemComposition c)
	{
		switch (id)
		{
			case ItemID.COINS_995:
			case ItemID.PLATINUM_TOKEN:
				return true;
			default:
				if (ActuallyTradeableItem.check(id))
				{
					return true;
				}
		}

		return c.isTradeable();
	}
}
