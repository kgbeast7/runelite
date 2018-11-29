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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.ScriptID;
import net.runelite.api.SkullIcon;
import net.runelite.api.SpriteID;
import net.runelite.api.Varbits;
import net.runelite.api.WidgetType;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Kept on Death",
	description = "Reworks the Items Kept on Death interface to be more accurate",
	enabledByDefault = false
)
@Slf4j
public class KeptOnDeathPlugin extends Plugin
{
	// Handles Clicking on items in Kept on Death Interface
	private static final int SCRIPT_ID = ScriptID.KEPT_LOST_ITEM_EXAMINE;

	// Item Container helpers
	private static final int MAX_ROW_ITEMS = 8;
	private static final int STARTING_X = 5;
	private static final int STARTING_Y = 25;
	private static final int X_INCREMENT = 40;
	private static final int Y_INCREMENT = 38;
	private static final int ORIGINAL_WIDTH = 36;
	private static final int ORIGINAL_HEIGHT = 32;
	private static final int ORIGINAL_LOST_HEIGHT = 209;
	private static final int ORIGINAL_LOST_Y = 107;

	// Information panel text helpers
	private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,###");
	private static final String MAX_KEPT_ITEMS_FORMAT = "<col=ffcc33>Max items kept on death :<br><br><col=ffcc33>~ %s ~";
	private static final String ACTION_TEXT = "Item: <col=ff981f>%s";
	private static final String DEFAULT = "<col=FFFFFF>3<col=FF981F> items protected by default";
	private static final String IS_SKULLED = "<col=ff3333>PK skull<col=ff981f> -3";
	private static final String PROTECTING_ITEM = "<col=ff3333>Protect Item prayer<col=ff981f> +1";
	private static final String ACTUAL = "Actually protecting <col=FFFFFF>%s<col=FF981F> items";
	private static final String WHITE_OUTLINE = "Items with a <col=ffffff>white outline<col=ff981f> will always be lost.";
	private static final String CHANGED_MECHANICS = "Untradeable items are kept on death in non-pvp scenarios.";
	private static final String NON_PVP = "You will have 1 hour to retrieve your lost items.";
	private static final String LINE_BREAK = "<br>";
	private static final int ORIGINAL_INFO_HEIGHT = 183;
	private static final int FONT_COLOR = 0xFF981F;

	// Button Names and Images
	private static final String PROTECT_ITEM_BUTTON_NAME = "Protect Item Prayer";
	private static final String SKULLED_BUTTON_NAME = "Skulled";
	private static final String LOW_WILDY_BUTTON_NAME = "Low Wildy (1-20)";
	private static final String DEEP_WILDY_BUTTON_NAME = "Deep Wildy (21+)";
	private static final int PROTECT_ITEM_SPRITE_ID = SpriteID.PRAYER_PROTECT_ITEM;
	private static final int SKULL_SPRITE_ID = SpriteID.PLAYER_KILLER_SKULL_523;
	private static final int SWORD_SPRITE_ID = SpriteID.MULTI_COMBAT_ZONE_CROSSED_SWORDS;
	private static final int SKULL_2_SPRITE_ID = SpriteID.FIGHT_PITS_WINNER_SKULL_RED;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Getter
	private boolean widgetVisible = false;

	private LinkedHashMap<String, WidgetButton> buttonMap = new LinkedHashMap<>();
	private boolean isSkulled = false;
	private boolean protectingItem = false;
	private boolean hasAlwaysLost = false;
	private int wildyLevel = -1;

	@Subscribe
	protected void onGameTick(GameTick t)
	{
		boolean old = widgetVisible;
		widgetVisible = client.getWidget(WidgetInfo.ITEMS_LOST_ON_DEATH_CONTAINER) != null;
		// TODO: Determine if they reopened widget without closing it.
		if (widgetVisible && !old)
		{
			if (isInSafeArea())
			{
				return;
			}

			// Above 20 wildy we have nothing new to display
			if (getCurrentWildyLevel() <= 20)
			{
				syncSettings();
				createWidgetButtons();
				recreateItemsKeptOnDeathWidget();
			}
		}
	}

	// Sync user settings
	private void syncSettings()
	{
		SkullIcon s = client.getLocalPlayer().getSkullIcon();
		isSkulled = (s != null && s.equals(SkullIcon.SKULL));
		protectingItem = client.getVar(Varbits.PRAYER_PROTECT_ITEM) == 1;
	}

	private int getCurrentWildyLevel()
	{
		if (client.getVar(Varbits.IN_WILDERNESS) != 1)
		{
			// if they are in a PvP world and not in a safe zone act like in lvl 1 wildy
			// if they are in wildy and in PvP world let normal wildy code execute.
			if (isInPvpWorld() && !isInPvPSafeZone())
			{
				wildyLevel = 1;
				return wildyLevel;
			}
			wildyLevel = -1;
			return wildyLevel;
		}

		int y = client.getLocalPlayer().getWorldLocation().getY();

		// Credits to atomicint_#5069 (Discord)
		int underLevel = ((y - 9920) / 8) + 1;
		int upperLevel = ((y - 3520) / 8) + 1;
		wildyLevel = (y > 6400 ? underLevel : upperLevel);
		return wildyLevel;
	}

	private boolean isInPvpWorld()
	{
		EnumSet<WorldType> world = client.getWorldType();
		return world.contains(WorldType.PVP) || world.contains(WorldType.PVP_HIGH_RISK);
	}

	private boolean isInPvPSafeZone()
	{
		Widget w = client.getWidget(WidgetInfo.PVP_WORLD_SAFE_ZONE);
		return w != null && !w.isHidden();
	}

	private boolean isInSafeArea()
	{
		// We must check last child since checking for the widget via client.getWidget doesn't work as expected
		Widget w = client.getWidget(WidgetInfo.ITEMS_KEPT_SAFE_ZONE_CONTAINER);
		return w != null && !w.isHidden();
	}

	private int getDefaultItemsKept()
	{
		int count = 3;

		if (isSkulled)
		{
			count = 0;
		}

		if (protectingItem)
		{
			count += 1;
		}

		return count;
	}

	private void recreateItemsKeptOnDeathWidget()
	{
		// Text flags based on items should be reset everytime the widget is recreated
		hasAlwaysLost = false;

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
			items.sort((o1, o2) ->
			{
				int exchangePrice2 = itemManager.getItemPrice(o2.getId());
				if (exchangePrice2 == 0)
				{
					exchangePrice2 = itemManager.getItemComposition(o2.getId()).getPrice();
				}
				int exchangePrice1 = itemManager.getItemPrice(o1.getId());
				if (exchangePrice1 == 0)
				{
					exchangePrice1 = itemManager.getItemComposition(o1.getId()).getPrice();
				}
				return exchangePrice2 - exchangePrice1;
			});

			int keepCount = getDefaultItemsKept();

			List<Widget> keptItems = new ArrayList<>();
			List<Widget> lostItems = new ArrayList<>();
			for (Item i : items)
			{
				int id = i.getId();
				if (id == -1)
				{
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

				// Bonds are always kept and do not count towards the limit.
				if (id == ItemID.OLD_SCHOOL_BOND || id == ItemID.OLD_SCHOOL_BOND_UNTRADEABLE)
				{
					keptItems.add(itemWidget);
					continue;
				}

				// Certain items are always lost on death and have a white outline which we need to readd
				AlwaysLostItem item = AlwaysLostItem.getByItemID(i.getId());
				if (item != null)
				{
					// Some of these items are kept on death (outside wildy), like the Rune pouch. Ignore them
					if (!item.isKept() || wildyLevel > 0)
					{
						itemWidget.setOnOpListener(SCRIPT_ID, 0, i.getQuantity(), c.getName());
						itemWidget.setBorderType(2);
						lostItems.add(itemWidget);
						hasAlwaysLost = true;
						continue;
					}
				}

				if (keepCount > 0 || (!checkTradeable(i.getId(), c) && wildyLevel < 21))
				{
					keepCount = keepCount < 0 ? -1 : keepCount - 1;

					// Certain items are turned into broken variants inside the wilderness.
					if (BrokenOnDeathItem.check(i.getId()))
					{
						keptItems.add(itemWidget);
						continue;
					}

					// Ignore all non tradeables in wildy except for the above case(s).
					if (wildyLevel > 0 && keepCount == -1)
					{
						lostItems.add(itemWidget);
						continue;
					}

					keptItems.add(itemWidget);
				}
				else
				{
					itemWidget.setOnOpListener(SCRIPT_ID, 0, i.getQuantity(), c.getName());
					lostItems.add(itemWidget);
				}
			}

			int rows = keptItems.size() > MAX_ROW_ITEMS ? keptItems.size() / MAX_ROW_ITEMS : 0;
			// Adjust items lost container position if new rows were added to kept items container
			lost.setOriginalY(ORIGINAL_LOST_Y + (rows * Y_INCREMENT));
			lost.setOriginalHeight(ORIGINAL_LOST_HEIGHT - (rows * Y_INCREMENT));
			setWidgetChildren(kept, keptItems);
			setWidgetChildren(lost, lostItems);

			updateKeptWidgetInfoText();
		}
	}

	/**
	 * Wrapper for widget.setChildren() but updates the child index and original positions
	 * Used for Items Kept and Lost containers
	 *
	 * @param parent  Widget to override children
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
	 * Creates the text to be displayed in the right side of the interface based on current selections
	 */
	private String getUpdatedInfoText()
	{
		String textToAdd = DEFAULT;

		if (isSkulled)
		{
			textToAdd += LINE_BREAK + IS_SKULLED;
		}

		if (protectingItem)
		{
			textToAdd += LINE_BREAK + PROTECTING_ITEM;
		}

		textToAdd += LINE_BREAK + String.format(ACTUAL, getDefaultItemsKept());

		if (wildyLevel < 1)
		{
			textToAdd += LINE_BREAK + LINE_BREAK + NON_PVP;
		}

		if (hasAlwaysLost)
		{
			textToAdd += LINE_BREAK + LINE_BREAK + WHITE_OUTLINE;
		}

		textToAdd += LINE_BREAK + LINE_BREAK + CHANGED_MECHANICS;

		return textToAdd;
	}

	/**
	 * Corrects the Information panel based on the item containers
	 */
	private void updateKeptWidgetInfoText()
	{
		// Add Information text widget
		createNewTextWidget();

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

	// isTradeable actually checks if they are traded on the grand exchange, some items are trade-able but not via GE
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

	private void createNewTextWidget()
	{
		// The text use to be put inside this container but since we can't create LAYER widgets
		// We need to edit this to be a layer for adding buttons.
		Widget old = client.getWidget(WidgetInfo.ITEMS_KEPT_INFORMATION_CONTAINER);

		// TEXT container would be the last child, if it exists create it.
		// client.getWidget() seems to not find indexed child widgets
		Widget[] children = old.getChildren();
		if (children != null && children.length > 0)
		{
			Widget x = old.getChild(children.length - 1);
			if (x.getId() == WidgetInfo.ITEMS_KEPT_CUSTOM_TEXT_CONTAINER.getId())
			{
				x.setText(getUpdatedInfoText());
				x.revalidate();
				return;
			}
		}

		Widget w = old.createChild(-1, WidgetType.TEXT);
		// Position under buttons taking remaining space
		w.setOriginalWidth(old.getOriginalWidth());
		w.setOriginalHeight(ORIGINAL_INFO_HEIGHT - old.getOriginalHeight());
		w.setOriginalY(old.getOriginalHeight());

		w.setFontId(FontID.PLAIN_11);
		w.setTextShadowed(true);
		w.setTextColor(FONT_COLOR);

		w.setText(getUpdatedInfoText());
		w.setId(WidgetInfo.ITEMS_KEPT_CUSTOM_TEXT_CONTAINER.getId());
		w.revalidate();

		// Need to reset height so text is visible?
		old.setOriginalHeight(ORIGINAL_INFO_HEIGHT);
		old.revalidate();
	}

	private void createWidgetButtons()
	{
		buttonMap.clear();

		createButton(PROTECT_ITEM_BUTTON_NAME, PROTECT_ITEM_SPRITE_ID, protectingItem);
		createButton(SKULLED_BUTTON_NAME, SKULL_SPRITE_ID, isSkulled);
		createButton(LOW_WILDY_BUTTON_NAME, SWORD_SPRITE_ID, wildyLevel > 0 && wildyLevel <= 20);
		createButton(DEEP_WILDY_BUTTON_NAME, SKULL_2_SPRITE_ID, wildyLevel > 20);

		Widget parent = client.getWidget(WidgetInfo.ITEMS_KEPT_INFORMATION_CONTAINER);
		parent.setType(WidgetType.LAYER);
		parent.revalidate();
		WidgetButton.addButtonsToContainerWidget(parent, buttonMap.values());
	}

	private void createButton(String name, int spriteID, boolean startingFlag)
	{
		WidgetButton button = new WidgetButton(name, spriteID, startingFlag, createRunnable(name), client);
		buttonMap.put(name, button);
	}

	private WidgetButtonRunnable createRunnable(String name)
	{
		return new WidgetButtonRunnable(name)
		{
			@Override
			public void run(boolean selected)
			{
				buttonCallback(this.getName(), selected);
			}
		};
	}

	private void buttonCallback(String name, boolean selected)
	{
		log.debug("Clicked Widget Button {}. New value: {}", name, selected);
		switch (name)
		{
			case PROTECT_ITEM_BUTTON_NAME:
				protectingItem = selected;
				break;
			case SKULLED_BUTTON_NAME:
				isSkulled = selected;
				break;
			case LOW_WILDY_BUTTON_NAME:
				if (!selected)
				{
					getCurrentWildyLevel();
					break;
				}
				wildyLevel = 1;
				buttonMap.get(DEEP_WILDY_BUTTON_NAME).setSelected(false);
				break;
			case DEEP_WILDY_BUTTON_NAME:
				if (!selected)
				{
					getCurrentWildyLevel();
					break;
				}
				wildyLevel = 21;
				buttonMap.get(LOW_WILDY_BUTTON_NAME).setSelected(false);
				break;
			default:
				log.warn("Unhandled Button Name: {}", name);
				return;
		}

		recreateItemsKeptOnDeathWidget();
	}
}