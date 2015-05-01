/*******************************************************************************
 * Copyright (c) 2015 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public
 * License v3.0 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * Contributors:
 * Jeff Martin - initial API and implementation
 ******************************************************************************/
package cuchaz.enigma;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.jar.JarFile;

import cuchaz.enigma.convert.ClassMatches;
import cuchaz.enigma.convert.MappingsConverter;
import cuchaz.enigma.convert.MatchesReader;
import cuchaz.enigma.convert.MemberMatches;
import cuchaz.enigma.mapping.*;

public class ConvertMain
{
	
	public static void main(String[] args) throws IOException,
		MappingParseException
	{

		// init files
		File home = new File(System.getProperty("user.home"));
		JarFile sourceJar =
			new JarFile(new File(home, ".minecraft/versions/1.8/1.8.jar"));
		JarFile destJar =
			new JarFile(new File(home, ".minecraft/versions/1.8.3/1.8.3.jar"));
		File inMappingsFile = new File("../Enigma Mappings/1.8.mappings");
		File outMappingsFile = new File("../Enigma Mappings/1.8.3.mappings");
		Mappings mappings =
			new MappingsReader().read(new FileReader(inMappingsFile));
		File classMatchesFile =
			new File(inMappingsFile.getName() + ".class.matches");
		File fieldMatchesFile =
			new File(inMappingsFile.getName() + ".field.matches");
		File methodMatchesFile =
			new File(inMappingsFile.getName() + ".method.matches");
		
		// match classes
		// computeClassMatches(classMatchesFile, sourceJar, destJar, mappings);
		// editClasssMatches(classMatchesFile, sourceJar, destJar, mappings);
		// convertMappings(outMappingsFile, sourceJar, destJar, mappings,
		// classMatchesFile);

		// match fields
		// computeFieldMatches(fieldMatchesFile, destJar, outMappingsFile,
		// classMatchesFile);
		// editFieldMatches(sourceJar, destJar, outMappingsFile, mappings,
		// classMatchesFile, fieldMatchesFile);
		// convertMappings(outMappingsFile, sourceJar, destJar, mappings,
		// classMatchesFile, fieldMatchesFile);

		// match methods/constructors
		// computeMethodMatches(methodMatchesFile, destJar, outMappingsFile,
		// classMatchesFile);
		// editMethodMatches(sourceJar, destJar, outMappingsFile, mappings,
		// classMatchesFile, methodMatchesFile);
		convertMappings(outMappingsFile, sourceJar, destJar, mappings,
			classMatchesFile, fieldMatchesFile, methodMatchesFile);
	}

	private static void convertMappings(File outMappingsFile,
		JarFile sourceJar, JarFile destJar, Mappings mappings,
		File classMatchesFile, File fieldMatchesFile, File methodMatchesFile)
		throws IOException
	{

		System.out.println("Reading matches...");
		ClassMatches classMatches = MatchesReader.readClasses(classMatchesFile);
		MemberMatches<FieldEntry> fieldMatches =
			MatchesReader.readMembers(fieldMatchesFile);
		MemberMatches<BehaviorEntry> methodMatches =
			MatchesReader.readMembers(methodMatchesFile);
		
		Deobfuscators deobfuscators = new Deobfuscators(sourceJar, destJar);
		deobfuscators.source.setMappings(mappings);

		// apply matches
		Mappings newMappings =
			MappingsConverter.newMappings(classMatches, mappings,
				deobfuscators.source, deobfuscators.dest);
		MappingsConverter.applyMemberMatches(newMappings, classMatches,
			fieldMatches, MappingsConverter.getFieldDoer());
		MappingsConverter.applyMemberMatches(newMappings, classMatches,
			methodMatches, MappingsConverter.getMethodDoer());

		// check the final mappings
		MappingsChecker checker =
			new MappingsChecker(deobfuscators.dest.getJarIndex());
		checker.dropBrokenMappings(newMappings);

		for(java.util.Map.Entry<ClassEntry, ClassMapping> mapping : checker
			.getDroppedClassMappings().entrySet())
			System.out.println("WARNING: Broken class entry "
				+ mapping.getKey() + " (" + mapping.getValue().getDeobfName()
				+ ")");
		for(java.util.Map.Entry<ClassEntry, ClassMapping> mapping : checker
			.getDroppedInnerClassMappings().entrySet())
			System.out.println("WARNING: Broken inner class entry "
				+ mapping.getKey() + " (" + mapping.getValue().getDeobfName()
				+ ")");
		for(java.util.Map.Entry<FieldEntry, FieldMapping> mapping : checker
			.getDroppedFieldMappings().entrySet())
			System.out.println("WARNING: Broken field entry "
				+ mapping.getKey() + " (" + mapping.getValue().getDeobfName()
				+ ")");
		for(java.util.Map.Entry<BehaviorEntry, MethodMapping> mapping : checker
			.getDroppedMethodMappings().entrySet())
			System.out.println("WARNING: Broken behavior entry "
				+ mapping.getKey() + " (" + mapping.getValue().getDeobfName()
				+ ")");

		// write out the converted mappings
		try(FileWriter out = new FileWriter(outMappingsFile))
		{
			new MappingsWriter().write(out, newMappings);
		}
		System.out.println("Wrote converted mappings to:\n\t"
			+ outMappingsFile.getAbsolutePath());
	}
	
	private static class Deobfuscators
	{

		public Deobfuscator source;
		public Deobfuscator dest;

		public Deobfuscators(JarFile sourceJar, JarFile destJar)
		{
			System.out.println("Indexing source jar...");
			IndexerThread sourceIndexer = new IndexerThread(sourceJar);
			sourceIndexer.start();
			System.out.println("Indexing dest jar...");
			IndexerThread destIndexer = new IndexerThread(destJar);
			destIndexer.start();
			sourceIndexer.joinOrBail();
			destIndexer.joinOrBail();
			source = sourceIndexer.deobfuscator;
			dest = destIndexer.deobfuscator;
		}
	}

	private static class IndexerThread extends Thread
	{

		private JarFile m_jarFile;
		public Deobfuscator deobfuscator;

		public IndexerThread(JarFile jarFile)
		{
			m_jarFile = jarFile;
			deobfuscator = null;
		}

		public void joinOrBail()
		{
			try
			{
				join();
			}catch(InterruptedException ex)
			{
				throw new Error(ex);
			}
		}
		
		@Override
		public void run()
		{
			try
			{
				deobfuscator = new Deobfuscator(m_jarFile);
			}catch(IOException ex)
			{
				throw new Error(ex);
			}
		}
	}
}
