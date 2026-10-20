package com.osrsmcp.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrsmcp.server.ServerStatus;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** Side panel showing MCP server status and connection helpers. */
public class OsrsMcpPanel extends PluginPanel
{
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final JLabel statusLabel = new JLabel();
	private final JLabel errorLabel = new JLabel();
	private final JLabel toolsLabel = new JLabel();
	private final JLabel tokenLabel = new JLabel();
	private final JButton showButton = new JButton("Show");
	private final JButton copyToken = new JButton("Copy token");
	private final JButton copyCode = new JButton("Copy Claude Code command");
	private final JButton copyDesktop = new JButton("Copy Claude Desktop config");

	private volatile ServerStatus status;
	private boolean tokenShown;

	@Inject
	public OsrsMcpPanel()
	{
		super();
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel body = new JPanel(new GridLayout(0, 1, 0, 6));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("OSRS MCP");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		body.add(title);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		body.add(statusLabel);
		errorLabel.setFont(FontManager.getRunescapeSmallFont());
		errorLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		body.add(errorLabel);
		toolsLabel.setFont(FontManager.getRunescapeSmallFont());
		toolsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		body.add(toolsLabel);

		JLabel tokenTitle = new JLabel("Auth token");
		tokenTitle.setForeground(Color.WHITE);
		body.add(tokenTitle);
		tokenLabel.setFont(FontManager.getRunescapeSmallFont());
		tokenLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		body.add(tokenLabel);

		JPanel tokenRow = new JPanel(new GridLayout(1, 2, 6, 0));
		tokenRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tokenRow.add(showButton);
		tokenRow.add(copyToken);
		body.add(tokenRow);

		body.add(copyCode);
		body.add(copyDesktop);

		showButton.addActionListener(e ->
		{
			tokenShown = !tokenShown;
			render();
		});
		copyToken.addActionListener(e -> copy(copyToken, "Copy token", s -> s.getToken()));
		copyCode.addActionListener(e -> copy(copyCode, "Copy Claude Code command", OsrsMcpPanel::claudeCodeCommand));
		copyDesktop.addActionListener(e -> copy(copyDesktop, "Copy Claude Desktop config", OsrsMcpPanel::claudeDesktopConfig));

		JLabel help = new JLabel("<html><body style='width:180px'>"
			+ "Keep RuneLite open while chatting; the server runs only while the plugin is enabled.<br><br>"
			+ "Data is read-only: assistants can look at your account but never act in the game. "
			+ "Overlay tools only draw hints and are optional.<br><br>"
			+ "To change the token, clear it in the plugin settings and a new one is generated."
			+ "</body></html>");
		help.setFont(FontManager.getRunescapeSmallFont());
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		help.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

		add(body, BorderLayout.NORTH);
		add(help, BorderLayout.CENTER);

		render();
	}

	/** Called from any thread whenever the server state changes. */
	public void update(ServerStatus newStatus)
	{
		status = newStatus;
		SwingUtilities.invokeLater(this::render);
	}

	static String claudeCodeCommand(ServerStatus s)
	{
		return "claude mcp add --transport http osrs " + s.url()
			+ " --header \"Authorization: Bearer " + s.getToken() + "\"";
	}

	static String claudeDesktopConfig(ServerStatus s)
	{
		JsonArray args = new JsonArray();
		args.add("-y");
		args.add("mcp-remote");
		args.add(s.url());
		args.add("--header");
		// No space after the colon: mcp-remote on Windows mishandles spaces in args
		args.add("Authorization:${AUTH_HEADER}");
		JsonObject env = new JsonObject();
		env.addProperty("AUTH_HEADER", "Bearer " + s.getToken());
		JsonObject server = new JsonObject();
		server.addProperty("command", "npx");
		server.add("args", args);
		server.add("env", env);
		JsonObject servers = new JsonObject();
		servers.add("osrs", server);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return PRETTY.toJson(root);
	}

	private interface Formatter
	{
		String format(ServerStatus s);
	}

	private void copy(JButton button, String label, Formatter formatter)
	{
		ServerStatus s = status;
		if (s == null || s.getToken() == null || s.getToken().isEmpty())
		{
			return;
		}
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(formatter.format(s)), null);
			button.setText("Copied!");
		}
		catch (RuntimeException e)
		{
			button.setText("Copy failed");
		}
		Timer t = new Timer(1500, e -> button.setText(label));
		t.setRepeats(false);
		t.start();
	}

	private void render()
	{
		ServerStatus s = status;
		boolean hasToken = s != null && s.getToken() != null && !s.getToken().isEmpty();
		if (s == null)
		{
			statusLabel.setText("Starting...");
			statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else if (s.isRunning())
		{
			statusLabel.setText("<html><font color='#46d246'>●</font> Running on " + s.url() + "</html>");
			statusLabel.setForeground(Color.WHITE);
		}
		else
		{
			statusLabel.setText("<html><font color='#e65050'>●</font> Stopped</html>");
			statusLabel.setForeground(Color.WHITE);
		}
		boolean hasError = s != null && s.getError() != null && !s.getError().isEmpty();
		errorLabel.setText(hasError ? "<html>" + escape(s.getError()) + "</html>" : "");
		errorLabel.setVisible(hasError);
		toolsLabel.setText(s == null ? "" : s.getToolCount() + " tools available");

		if (!hasToken)
		{
			tokenLabel.setText("(no token yet)");
		}
		else if (tokenShown)
		{
			tokenLabel.setText(s.getToken());
		}
		else
		{
			tokenLabel.setText("••••••••••••");
		}
		showButton.setText(tokenShown ? "Hide" : "Show");
		copyToken.setEnabled(hasToken);
		copyCode.setEnabled(hasToken && s.isRunning());
		copyDesktop.setEnabled(hasToken && s.isRunning());
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
