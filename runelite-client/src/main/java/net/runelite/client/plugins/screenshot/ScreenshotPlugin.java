/*
 * Copyright (c) 2018, Lotto <https://github.com/devLotto>
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
package net.runelite.client.plugins.screenshot;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Provides;
import java.awt.Desktop;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.SpriteID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.LocalPlayerDeath;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import static net.runelite.api.widgets.WidgetID.BARROWS_REWARD_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.CHAMBERS_OF_XERIC_REWARD_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.CLUE_SCROLL_REWARD_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.DIALOG_SPRITE_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.KINGDOM_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.LEVEL_UP_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.QUEST_COMPLETED_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.THEATRE_OF_BLOOD_REWARD_GROUP_ID;
import net.runelite.api.widgets.WidgetInfo;
import static net.runelite.client.RuneLite.SCREENSHOT_DIR;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.ScreenCapture;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Screenshot",
	description = "Enable the manual and automatic taking of screenshots",
	tags = {"external", "images", "imgur", "integration", "notifications"}
)
@Slf4j
public class ScreenshotPlugin extends Plugin
{
	private static final DateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

	private static final Pattern NUMBER_PATTERN = Pattern.compile("([0-9]+)");
	private static final Pattern LEVEL_UP_PATTERN = Pattern.compile(".*Your ([a-zA-Z]+) (?:level is|are)? now (\\d+)\\.");
	private static final Pattern BOSSKILL_MESSAGE_PATTERN = Pattern.compile("Your (.+) kill count is: <col=ff0000>(\\d+)</col>.");
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(".*(Valuable|Untradeable) drop: ([^<>]+)(?:</col>)?");
	private static final ImmutableList<String> PET_MESSAGES = ImmutableList.of("You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed");

	private static final ImmutableList<String> KILL_MESSAGES = ImmutableList.of("into tiny pieces and sat on them", "you have obliterated",
		"falls before your might", "A humiliating defeat for", "With a crushing blow you", "thinking challenging you",
		"Can anyone defeat you? Certainly", "was no match for you", "You were clearly a better fighter than", "RIP",
		"You have defeated", "What an embarrassing performance by", "was no match for your awesomeness");

	private static String format(Date date)
	{
		synchronized (TIME_FORMAT)
		{
			return TIME_FORMAT.format(date);
		}
	}

	private String clueType;
	private Integer clueNumber;

	private Integer barrowsNumber;

	private Integer chambersOfXericNumber;

	private Integer theatreOfBloodNumber;

	private boolean shouldTakeScreenshot;

	@Inject
	private ScreenshotConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ScreenshotOverlay screenshotOverlay;

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUi;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private DrawManager drawManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ScreenCapture screenCapture;

	@Getter(AccessLevel.PACKAGE)
	private BufferedImage reportButton;

	private NavigationButton titleBarButton;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.hotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			takeScreenshot(null);
		}
	};

	@Provides
	ScreenshotConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ScreenshotConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(screenshotOverlay);
		SCREENSHOT_DIR.mkdirs();
		keyManager.registerKeyListener(hotkeyListener);

		final BufferedImage iconImage = ImageUtil.getResourceStreamFromClass(getClass(), "screenshot.png");

		titleBarButton = NavigationButton.builder()
			.tab(false)
			.tooltip("Take screenshot")
			.icon(iconImage)
			.onClick(() -> takeScreenshot(null))
			.popup(ImmutableMap
				.<String, Runnable>builder()
				.put("Open screenshot folder...", () ->
				{
					try
					{
						Desktop.getDesktop().open(SCREENSHOT_DIR);
					}
					catch (IOException ex)
					{
						log.warn("Error opening screenshot dir", ex);
					}
				})
				.build())
			.build();

		clientToolbar.addNavigation(titleBarButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(screenshotOverlay);
		clientToolbar.removeNavigation(titleBarButton);
		keyManager.unregisterKeyListener(hotkeyListener);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN
			&& reportButton == null)
		{
			reportButton = spriteManager.getSprite(SpriteID.CHATBOX_REPORT_BUTTON, 0);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!shouldTakeScreenshot)
		{
			return;
		}

		shouldTakeScreenshot = false;

		String fileName = null;
		if (client.getWidget(WidgetInfo.LEVEL_UP_LEVEL) != null)
		{
			fileName = parseLevelUpWidget(WidgetInfo.LEVEL_UP_LEVEL);
		}
		else if (client.getWidget(WidgetInfo.DIALOG_SPRITE_TEXT) != null)
		{
			fileName = parseLevelUpWidget(WidgetInfo.DIALOG_SPRITE_TEXT);
		}
		else if (client.getWidget(WidgetInfo.QUEST_COMPLETED_NAME_TEXT) != null)
		{
			// "You have completed The Corsair Curse!"
			String text = client.getWidget(WidgetInfo.QUEST_COMPLETED_NAME_TEXT).getText();
			fileName = "Quest(" + text.substring(19, text.length() - 1) + ")";
		}

		if (fileName != null)
		{
			takeScreenshot(fileName);
		}
	}

	@Subscribe
	public void onLocalPlayerDeath(LocalPlayerDeath death)
	{
		if (config.screenshotPlayerDeath())
		{
			takeScreenshot("Death " + format(new Date()));
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.SERVER && event.getType() != ChatMessageType.FILTERED)
		{
			return;
		}

		String chatMessage = event.getMessage();

		if (chatMessage.contains("You have completed") && chatMessage.contains("Treasure"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				clueNumber = Integer.valueOf(m.group());
				clueType = chatMessage.substring(chatMessage.lastIndexOf(m.group()) + m.group().length() + 1, chatMessage.indexOf("Treasure") - 1);
				return;
			}
		}

		if (chatMessage.startsWith("Your Barrows chest count is"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				barrowsNumber = Integer.valueOf(m.group());
				return;
			}
		}

		if (chatMessage.startsWith("Your completed Chambers of Xeric count is:"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				chambersOfXericNumber = Integer.valueOf(m.group());
				return;
			}
		}

		if (chatMessage.startsWith("Your completed Theatre of Blood count is:"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				theatreOfBloodNumber = Integer.valueOf(m.group());
				return;
			}
		}

		if (config.screenshotPet() && PET_MESSAGES.stream().anyMatch(chatMessage::contains))
		{
			String fileName = "Pet " + format(new Date());
			takeScreenshot(fileName);
		}

		if (config.screenshotKills() && KILL_MESSAGES.stream().anyMatch(chatMessage::contains))
		{
			String fileName = "Kill " + format(new Date());
			takeScreenshot(fileName);
		}

		if (config.screenshotBossKills() )
		{
			Matcher m = BOSSKILL_MESSAGE_PATTERN.matcher(chatMessage);
			if (m.matches())
			{
				String bossName = m.group(1);
				String bossKillcount = m.group(2);
				String fileName = bossName + "(" + bossKillcount + ")";
				takeScreenshot(fileName);
			}
		}

		if (config.screenshotValuableDrop())
		{
			Matcher m = VALUABLE_DROP_PATTERN.matcher(chatMessage);
			if (m.matches())
			{
				String valuableDropType = m.group(1);
				String valuableDropName = m.group(2);
				String fileName = valuableDropType + " drop " + valuableDropName + " " + format(new Date());
				takeScreenshot(fileName);
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		String fileName;
		int groupId = event.getGroupId();

		switch (groupId)
		{
			case QUEST_COMPLETED_GROUP_ID:
			case CLUE_SCROLL_REWARD_GROUP_ID:
			case CHAMBERS_OF_XERIC_REWARD_GROUP_ID:
			case THEATRE_OF_BLOOD_REWARD_GROUP_ID:
			case BARROWS_REWARD_GROUP_ID:
				if (!config.screenshotRewards())
				{
					return;
				}
				break;
			case LEVEL_UP_GROUP_ID:
			case DIALOG_SPRITE_GROUP_ID:
				if (!config.screenshotLevels())
				{
					return;
				}
				break;
			case KINGDOM_GROUP_ID:
				if (!config.screenshotKingdom())
				{
					return;
				}
				break;
		}

		switch (groupId)
		{
			case KINGDOM_GROUP_ID:
			{
				fileName = "Kingdom " + LocalDate.now();
				takeScreenshot(fileName);
				break;
			}
			case CHAMBERS_OF_XERIC_REWARD_GROUP_ID:
			{
				if (chambersOfXericNumber == null)
				{
					return;
				}

				fileName = "Chambers of Xeric(" + chambersOfXericNumber + ")";
				chambersOfXericNumber = null;
				break;
			}
			case THEATRE_OF_BLOOD_REWARD_GROUP_ID:
			{
				if (theatreOfBloodNumber == null)
				{
					return;
				}

				fileName = "Theatre of Blood(" + theatreOfBloodNumber + ")";
				theatreOfBloodNumber = null;
				break;
			}
			case BARROWS_REWARD_GROUP_ID:
			{
				if (barrowsNumber == null)
				{
					return;
				}

				fileName = "Barrows(" + barrowsNumber + ")";
				barrowsNumber = null;
				break;
			}
			case LEVEL_UP_GROUP_ID:
			case DIALOG_SPRITE_GROUP_ID:
			case QUEST_COMPLETED_GROUP_ID:
			{
				// level up widget gets loaded prior to the text being set, so wait until the next tick
				shouldTakeScreenshot = true;
				return;
			}
			case CLUE_SCROLL_REWARD_GROUP_ID:
			{
				if (clueType == null || clueNumber == null)
				{
					return;
				}

				fileName = Character.toUpperCase(clueType.charAt(0)) + clueType.substring(1) + "(" + clueNumber + ")";
				clueType = null;
				clueNumber = null;
				break;
			}
			default:
				return;
		}

		takeScreenshot(fileName);
	}

	/**
	 * Receives a WidgetInfo pointing to the middle widget of the level-up dialog,
	 * and parses it into a shortened string for filename usage.
	 *
	 * @param levelUpLevel WidgetInfo pointing to the required text widget,
	 *                     with the format "Your Skill (level is/are) now 99."
	 * @return Shortened string in the format "Skill(99)"
	 */
	String parseLevelUpWidget(WidgetInfo levelUpLevel)
	{
		Widget levelChild = client.getWidget(levelUpLevel);
		if (levelChild == null)
		{
			return null;
		}

		Matcher m = LEVEL_UP_PATTERN.matcher(levelChild.getText());
		if (!m.matches())
		{
			return null;
		}

		String skillName = m.group(1);
		String skillLevel = m.group(2);
		return skillName + "(" + skillLevel + ")";
	}

	/**
	 * Saves a screenshot of the client window to the screenshot folder as a PNG,
	 * and optionally uploads it to an image-hosting service.
	 *
	 * @param fileName    Filename to use, without file extension.
	 */
	private void takeScreenshot(String fileName)
	{
		Consumer<Image> screenshotConsumer = image ->
		{
			BufferedImage screenshot = config.includeFrame()
				? new BufferedImage(clientUi.getWidth(), clientUi.getHeight(), BufferedImage.TYPE_INT_ARGB)
				: new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);

			Graphics graphics = screenshot.getGraphics();

			int gameOffsetX = 0;
			int gameOffsetY = 0;

			if (config.includeFrame())
			{
				// Draw the client frame onto the screenshot
				clientUi.paint(graphics);

				// Evaluate the position of the game inside the frame
				final Point canvasOffset = clientUi.getCanvasOffset();
				gameOffsetX = canvasOffset.getX();
				gameOffsetY = canvasOffset.getY();
			}

			// Draw the game onto the screenshot
			graphics.drawImage(image, gameOffsetX, gameOffsetY, null);
			screenCapture.takeScreenshot(screenshot, fileName, config.notifyWhenTaken(), config.uploadScreenshot());
		};

		if (config.displayDate())
			screenshotOverlay.queueForTimestamp(screenshotConsumer);
		else
			drawManager.requestNextFrameListener(screenshotConsumer);
	}

	@VisibleForTesting
	int getClueNumber()
	{
		return clueNumber;
	}

	@VisibleForTesting
	String getClueType()
	{
		return clueType;
	}

	@VisibleForTesting
	int getBarrowsNumber()
	{
		return barrowsNumber;
	}

	@VisibleForTesting
	int getChambersOfXericNumber()
	{
		return chambersOfXericNumber;
	}

	@VisibleForTesting
	int gettheatreOfBloodNumber()
	{
		return theatreOfBloodNumber;
	}
}
