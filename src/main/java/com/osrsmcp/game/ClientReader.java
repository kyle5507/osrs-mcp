package com.osrsmcp.game;

import com.osrsmcp.tools.ToolException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;

/**
 * Runs game-state reads on the client thread from HTTP worker threads.
 * All RuneLite API access from tools must go through here.
 */
@Singleton
public class ClientReader
{
	private static final long TIMEOUT_MS = 2000;

	private final Client client;
	private final ClientThread clientThread;

	@Inject
	public ClientReader(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/** Run {@code reader} on the client thread and wait for the result. */
	public <T> T read(Supplier<T> reader) throws ToolException
	{
		if (client.isClientThread())
		{
			return reader.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				future.complete(reader.get());
			}
			catch (Throwable t)
			{
				future.completeExceptionally(t);
			}
		});
		try
		{
			return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e)
		{
			throw new ToolException("Timed out waiting for the game client");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new ToolException("Interrupted");
		}
		catch (ExecutionException e)
		{
			Throwable cause = e.getCause();
			if (cause instanceof ToolRuntimeException)
			{
				throw new ToolException(cause.getMessage());
			}
			throw new RuntimeException(cause);
		}
	}

	/** Like {@link #read} but fails with a clear message unless the player is logged in. */
	public <T> T readLoggedIn(Supplier<T> reader) throws ToolException
	{
		return read(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
			{
				throw new ToolRuntimeException("Not logged in. Log in to the game in RuneLite first.");
			}
			return reader.get();
		});
	}

	/**
	 * Throw from inside a {@link #read} supplier to report a user-facing error
	 * (it is converted to a {@link ToolException}).
	 */
	public static class ToolRuntimeException extends RuntimeException
	{
		public ToolRuntimeException(String message)
		{
			super(message);
		}
	}
}
