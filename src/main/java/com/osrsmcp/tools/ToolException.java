package com.osrsmcp.tools;

/** An error reported back to the model as a tool result with isError=true. */
public class ToolException extends Exception
{
	public ToolException(String message)
	{
		super(message);
	}
}
