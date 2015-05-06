/*
 * Copyright © 2015 | Alexander01998 | All rights reserved.
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package tk.wurst_client.enigma.regexlist;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.regex.PatternSyntaxException;

public class RegexListReader
{
	public ArrayList<RegexListEntry> read(File file) throws IOException
	{
		final char[] buffer = new char[8192];
		final StringBuilder output = new StringBuilder();
		Reader reader = new FileReader(file);
		for(int length; (length = reader.read(buffer, 0, 8192)) > 0;)
			output.append(buffer, 0, length);
		reader.close();
		String content = output.toString();
		String[] lines = content.split("\n");
		ArrayList<RegexListEntry> regexList = new ArrayList<RegexListEntry>();
		for(int i = 0; i < lines.length; i++)
		{
			String[] data = lines[i].split("\t");
			if(data.length < 3)
				continue;
			try
			{
				if(data[0].equals("*"))
					regexList.add(new RegexListEntry(data[1], data[2]));
				else
					regexList.add(new RegexListEntry(data[0].split("|"),
						data[1], data[2]));
			}catch(PatternSyntaxException e)
			{
				throw new IOException("Invalid regex on line " + (i + 1)
					+ ":\n" + e.getMessage());
			}
		}
		return regexList;
	}
}
