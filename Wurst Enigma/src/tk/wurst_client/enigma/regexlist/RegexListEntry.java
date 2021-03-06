/*
 * Copyright � 2015 | Alexander01998 | All rights reserved.
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package tk.wurst_client.enigma.regexlist;

import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.common.collect.Sets;

public class RegexListEntry
{
	private final HashSet<String> target;
	private final Pattern regex;
	private final String replacement;
	
	public RegexListEntry(String regex, String replacement)
		throws PatternSyntaxException
	{
		this(null, regex, replacement);
	}
	
	public RegexListEntry(String[] target, String regex, String replacement)
		throws PatternSyntaxException
	{
		this.target = target == null ? null : Sets.newHashSet(target);
		this.regex = Pattern.compile(regex);
		this.replacement = replacement;
	}
	
	public boolean isTarget(String name)
	{
		if(target == null)
			return true;
		if(target.contains(name))
			return true;
		return false;
	}
	
	public String replaceAll(String content) throws IllegalArgumentException
	{
		return regex.matcher(content).replaceAll(replacement);
	}
}
