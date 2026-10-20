package com.osrsmcp.ge;

import com.osrsmcp.OsrsMcpConfig;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Helps the player place the offers the AI staged. Everything here is visual or click-to-fill: the
 * player clicks (or presses the optional hotkey) to fill a value and always presses Enter / Confirm
 * themselves. Techniques follow Flipping Copilot (offer screen detection, chatbox fill, previous-search
 * style entries in the GE search results) and Flipping Utilities (clickable chatbox text widget).
 */
@Slf4j
@Singleton
public class GeAssist
{
	// Chatbox numeric prompt modes: 32-bit (quantity) and 64-bit (price). Flipping Copilot OfferHandler.
	static final int MESLAYER_MODE_INT_INPUT = 7;
	static final int MESLAYER_MODE_LONG_INPUT = 30;
	// Chatbox mode of the GE item search (Flipping Copilot GameUiChangesHandler, GE Filters SearchFilter).
	static final int MESLAYER_MODE_ITEM_SEARCH = 14;
	// GE offer setup price varp. 64-bit since the 2026-09-30 max-cash update and unnamed in RuneLite 1.13.1
	// (Flipping Copilot GrandExchange.GE_SETUP_PRICE_VARP).
	static final int GE_SETUP_PRICE_VARP = 5753;
	// Script run by a GE search result to select that item (Flipping Copilot GePreviousSearch).
	static final int SCRIPT_GE_SELECT_ITEM = 754;
	static final int SELECT_ARG = 84;
	static final int SELECT_KEY_ARG = -2147483640;

	private static final String QTY_BUY_TITLE = "How many do you wish to buy?";
	private static final String QTY_SELL_TITLE = "How many do you wish to sell?";
	private static final String PRICE_TITLE = "Set a price for each item:";

	private static final int TEXT_COLOR = 0x0040FF;
	private static final int TEXT_HOVER = 0xFFFFFF;
	private static final int ROW_HEIGHT = 32;
	private static final int MAX_SEARCH_ROWS = 8;
	private static final long LOGIN_GRACE_MS = 4000;

	/** Cached comparison between the GE offer screen and the staged offers. Immutable. */
	static final class View
	{
		static final View CLOSED = new View(false, false, -1, false, 0, 0, null);

		final boolean geOpen;
		final boolean setupOpen;
		final int item;
		final boolean sell;
		final int quantity;
		final long price;
		/** Pending offer matching current item and type, or null. */
		final StagedOffers.Offer match;

		View(boolean geOpen, boolean setupOpen, int item, boolean sell, int quantity, long price,
			StagedOffers.Offer match)
		{
			this.geOpen = geOpen;
			this.setupOpen = setupOpen;
			this.item = item;
			this.sell = sell;
			this.quantity = quantity;
			this.price = price;
			this.match = match;
		}
	}

	private enum Kind
	{
		NONE, QUANTITY, PRICE
	}

	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final KeyManager keyManager;
	private final OverlayManager overlayManager;
	private final ItemManager itemManager;
	private final OsrsMcpConfig config;
	private final StagedOffers staged;
	private final GeAssistOverlay overlay;
	private final GeSellHighlightOverlay sellOverlay;
	private final GeButtonHighlightOverlay buttonOverlay;

	private volatile View view = View.CLOSED;
	/** Value the hotkey would fill right now, or -1. Written on the client thread, read on the AWT thread. */
	private volatile long hotkeyValue = -1;
	private volatile long ignoreOffersUntil;

	// client thread only
	private Widget fillWidget;
	private Kind fillKind = Kind.NONE;

	private final KeyListener keyListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			Keybind bind = config.geAssistHotkey();
			long value = hotkeyValue;
			// Enter would also submit the value, so it can never be the fill key
			if (value < 0 || bind == null || Keybind.NOT_SET.equals(bind) || e.getKeyCode() == KeyEvent.VK_ENTER
				|| !bind.matches(e))
			{
				return;
			}
			e.consume();
			clientThread.invoke(() ->
			{
				if (hotkeyValue == value)
				{
					fillChatbox(value);
				}
			});
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};

	@Inject
	public GeAssist(Client client, ClientThread clientThread, EventBus eventBus, KeyManager keyManager,
		OverlayManager overlayManager, ItemManager itemManager, OsrsMcpConfig config, StagedOffers staged)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.keyManager = keyManager;
		this.overlayManager = overlayManager;
		this.itemManager = itemManager;
		this.config = config;
		this.staged = staged;
		this.overlay = new GeAssistOverlay(this, config, staged);
		this.sellOverlay = new GeSellHighlightOverlay(this, config, staged, itemManager);
		this.buttonOverlay = new GeButtonHighlightOverlay(client, this, config);
	}

	View view()
	{
		return view;
	}

	void startUp()
	{
		eventBus.register(this);
		keyManager.registerKeyListener(keyListener);
		overlayManager.add(overlay);
		overlayManager.add(sellOverlay);
		overlayManager.add(buttonOverlay);
		clientThread.invoke(() ->
		{
			staged.refresh();
			refresh();
		});
	}

	void shutDown()
	{
		keyManager.unregisterKeyListener(keyListener);
		overlayManager.remove(overlay);
		overlayManager.remove(sellOverlay);
		overlayManager.remove(buttonOverlay);
		eventBus.unregister(this);
		hotkeyValue = -1;
		clientThread.invoke(() ->
		{
			clearFill();
			view = View.CLOSED;
		});
	}

	/** Recompute the cached view; client thread only. Also called by the tools after staging. */
	void refresh()
	{
		if (!config.geAssist())
		{
			view = View.CLOSED;
			clearFill();
			return;
		}
		Widget ge = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		if (ge == null || ge.isHidden())
		{
			view = View.CLOSED;
			clearFill();
			return;
		}
		Widget confirm = client.getWidget(InterfaceID.GeOffers.SETUP_CONFIRM);
		boolean setup = confirm != null && !confirm.isHidden();
		if (!setup)
		{
			view = new View(true, false, -1, false, 0, 0, null);
			return;
		}
		int item = itemManager.canonicalize(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH));
		boolean sell = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
		int qty = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
		long price = client.getVarpLongValue(GE_SETUP_PRICE_VARP);
		StagedOffers.Offer match = null;
		if (item > 0)
		{
			for (StagedOffers.Offer o : staged.pending())
			{
				if (o.itemId == item && o.buy != sell)
				{
					match = o;
					break;
				}
			}
		}
		view = new View(true, true, item, sell, qty, price, match);
		if (fillKind != Kind.NONE)
		{
			updateFill();
		}
	}

	// ---- events ----

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			clientThread.invokeLater(this::refresh);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			refresh();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		refresh();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (view.geOpen)
		{
			refresh();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// offers are re-announced on login; do not treat them as freshly placed
			ignoreOffersUntil = System.currentTimeMillis() + LOGIN_GRACE_MS;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			view = View.CLOSED;
			fillWidget = null;
			fillKind = Kind.NONE;
			hotkeyValue = -1;
		}
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() != VarClientID.MESLAYERMODE || !config.geAssist())
		{
			return;
		}
		int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
		if (mode == MESLAYER_MODE_ITEM_SEARCH)
		{
			// give the interface script a tick to build the results container
			clientThread.invokeLater(this::showSearchRows);
		}
		else if (mode == MESLAYER_MODE_INT_INPUT || mode == MESLAYER_MODE_LONG_INPUT)
		{
			clientThread.invokeLater(this::showFill);
		}
		else
		{
			clearFill();
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();
		if (offer == null || System.currentTimeMillis() < ignoreOffersUntil)
		{
			return;
		}
		GrandExchangeOfferState state = offer.getState();
		if (state != GrandExchangeOfferState.BUYING && state != GrandExchangeOfferState.SELLING)
		{
			return;
		}
		boolean buy = state == GrandExchangeOfferState.BUYING;
		int item = itemManager.canonicalize(offer.getItemId());
		for (StagedOffers.Offer o : staged.pending())
		{
			if (o.buy == buy && o.itemId == item && o.price == offer.getPrice()
				&& o.quantity == offer.getTotalQuantity())
			{
				staged.markPlaced(o.id);
				log.debug("Staged GE offer {} was placed", o.id);
				refresh();
				return;
			}
		}
	}

	// ---- price / quantity click-to-fill (Flipping Utilities OfferEditor, Flipping Copilot OfferHandler) ----

	private Kind inputKind()
	{
		Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (title == null || title.getText() == null)
		{
			return Kind.NONE;
		}
		String t = title.getText();
		if (t.equals(QTY_BUY_TITLE) || t.equals(QTY_SELL_TITLE))
		{
			return Kind.QUANTITY;
		}
		if (t.equals(PRICE_TITLE))
		{
			return Kind.PRICE;
		}
		return Kind.NONE;
	}

	private void showFill()
	{
		clearFill();
		int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
		if ((mode != MESLAYER_MODE_INT_INPUT && mode != MESLAYER_MODE_LONG_INPUT) || !config.geAssist())
		{
			return;
		}
		refresh();
		View v = view;
		Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (!v.setupOpen || client.getWidget(InterfaceID.GeOffers.SETUP) == null || parent == null)
		{
			return;
		}
		Kind kind = inputKind();
		if (kind == Kind.NONE || v.match == null)
		{
			return;
		}
		fillKind = kind;
		Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (title != null)
		{
			// make room for our line below the title (as Flipping Copilot does)
			title.setOriginalY(title.getOriginalY() + 7);
			title.revalidate();
		}
		Widget text = parent.createChild(-1, WidgetType.TEXT);
		text.setTextColor(TEXT_COLOR);
		text.setFontId(FontID.VERDANA_11_BOLD);
		text.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		text.setOriginalX(10);
		text.setOriginalY(40);
		text.setOriginalHeight(20);
		text.setXTextAlignment(WidgetTextAlignment.LEFT);
		text.setWidthMode(WidgetSizeMode.MINUS);
		text.setHasListener(true);
		text.setOnMouseRepeatListener((JavaScriptCallback) ev -> text.setTextColor(TEXT_HOVER));
		text.setOnMouseLeaveListener((JavaScriptCallback) ev -> text.setTextColor(TEXT_COLOR));
		text.revalidate();
		fillWidget = text;
		updateFill();
	}

	/** Point the line (and the hotkey) at the value of the currently matching staged offer. */
	private void updateFill()
	{
		View v = view;
		if (fillWidget == null || fillKind == Kind.NONE || v.match == null)
		{
			hotkeyValue = -1;
			return;
		}
		final long value = fillKind == Kind.PRICE ? v.match.price : v.match.quantity;
		String label = fillKind == Kind.PRICE ? "Click to set price: " : "Click to set quantity: ";
		StringBuilder sb = new StringBuilder(label).append(String.format("%,d", value)).append(" (staged)");
		Keybind bind = config.geAssistHotkey();
		if (bind != null && !Keybind.NOT_SET.equals(bind))
		{
			sb.append(" [").append(bind).append("]");
		}
		fillWidget.setText(sb.toString());
		fillWidget.setAction(0, fillKind == Kind.PRICE ? "Set price" : "Set quantity");
		fillWidget.setOnOpListener((JavaScriptCallback) ev -> fillChatbox(value));
		fillWidget.revalidate();
		hotkeyValue = value;
	}

	/** Puts the value in the chatbox input. The player still presses Enter. */
	private void fillChatbox(long value)
	{
		Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (input == null)
		{
			return;
		}
		input.setText(value + "*");
		client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(value));
	}

	private void clearFill()
	{
		hotkeyValue = -1;
		fillKind = Kind.NONE;
		if (fillWidget != null)
		{
			fillWidget.setHidden(true);
			fillWidget = null;
		}
	}

	// ---- staged buys in the GE item search (Flipping Copilot GePreviousSearch) ----

	private void showSearchRows()
	{
		if (!config.geAssist() || client.getVarcIntValue(VarClientID.MESLAYERMODE) != MESLAYER_MODE_ITEM_SEARCH)
		{
			return;
		}
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		Widget ge = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		if (results == null || ge == null || ge.isHidden())
		{
			return;
		}
		String typed = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
		if (typed != null && !typed.isEmpty())
		{
			return;
		}
		List<StagedOffers.Offer> buys = new ArrayList<>();
		for (StagedOffers.Offer o : staged.pending())
		{
			if (o.buy && buys.size() < MAX_SEARCH_ROWS)
			{
				buys.add(o);
			}
		}
		if (buys.isEmpty())
		{
			return;
		}
		// The interface script rebuilds this container as soon as the player types, which removes our rows.
		results.deleteAllChildren();
		int row = 0;
		for (StagedOffers.Offer o : buys)
		{
			int y = row++ * ROW_HEIGHT;
			Widget rect = results.createChild(-1, WidgetType.RECTANGLE);
			rect.setTextColor(0xFFFFFF);
			rect.setOpacity(255);
			rect.setFilled(true);
			rect.setName("<col=ff9040>" + o.itemName + "</col>");
			rect.setOriginalX(114);
			rect.setOriginalY(y);
			rect.setOriginalWidth(256);
			rect.setOriginalHeight(ROW_HEIGHT);
			rect.setHasListener(true);
			rect.setAction(0, "Select");
			rect.setOnOpListener(SCRIPT_GE_SELECT_ITEM, o.itemId, SELECT_ARG);
			rect.setOnKeyListener(SCRIPT_GE_SELECT_ITEM, o.itemId, SELECT_KEY_ARG);
			rect.setOnMouseOverListener((JavaScriptCallback) ev -> rect.setOpacity(200));
			rect.setOnMouseLeaveListener((JavaScriptCallback) ev -> rect.setOpacity(255));
			rect.revalidate();

			Widget icon = results.createChild(-1, WidgetType.GRAPHIC);
			icon.setItemId(o.itemId);
			icon.setItemQuantity(1);
			icon.setItemQuantityMode(0);
			icon.setRotationX(550);
			icon.setModelZoom(1031);
			icon.setBorderType(1);
			icon.setOriginalX(118);
			icon.setOriginalY(y);
			icon.setOriginalWidth(36);
			icon.setOriginalHeight(ROW_HEIGHT);
			icon.revalidate();

			Widget label = results.createChild(-1, WidgetType.TEXT);
			label.setText("Staged: " + o.itemName + " x" + String.format("%,d", o.quantity) + " @ "
				+ String.format("%,d", o.price));
			label.setFontId(495);
			label.setOriginalX(158);
			label.setOriginalY(y);
			label.setOriginalWidth(212);
			label.setOriginalHeight(ROW_HEIGHT);
			label.setYTextAlignment(1);
			label.revalidate();
		}
		results.setScrollHeight(row * ROW_HEIGHT);
		results.revalidateScroll();
	}
}
