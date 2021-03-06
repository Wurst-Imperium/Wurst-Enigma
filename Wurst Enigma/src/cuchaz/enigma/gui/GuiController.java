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
package cuchaz.enigma.gui;

import java.awt.Font;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;
import javax.swing.JTextArea;

import tk.wurst_client.enigma.regexlist.RegexListEntry;
import tk.wurst_client.enigma.regexlist.RegexListReader;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.strobel.decompiler.languages.java.ast.CompilationUnit;

import cuchaz.enigma.Deobfuscator;
import cuchaz.enigma.Deobfuscator.ProgressListener;
import cuchaz.enigma.analysis.*;
import cuchaz.enigma.gui.ProgressDialog.ProgressRunnable;
import cuchaz.enigma.mapping.*;

public class GuiController
{
	
	private Deobfuscator m_deobfuscator;
	private Gui m_gui;
	private SourceIndex m_index;
	private ClassEntry m_currentObfClass;
	private boolean m_isDirty;
	private Deque<EntryReference<Entry, Entry>> m_referenceStack;
	private static final AtomicInteger counter = new AtomicInteger();
	/**
	 * <p>
	 * <img src="https://www.debuggex.com/i/l2IVvEmQvol11QAf.png">
	 * <p>
	 * <a href="https://www.debuggex.com/r/l2IVvEmQvol11QAf">View on
	 * Debuggex</a>
	 * <p>
	 * Fixes generic types by converting things like
	 * <p>
	 * <code>List&lt;String&gt; v1 = (List&lt;String&gt;)Lists.newArrayList();</code>
	 * <p>
	 * to
	 * <p>
	 * <code>List&lt;String&gt; v1 = Lists.&lt;String&gt;newArrayList();</code>
	 */
	private Pattern generics =
		Pattern
			.compile("\\((?:\\w+)\\<((?:\\w{2,}(?:\\<(?:\\w+|\\?(?: extends \\w+)?|, )+\\>|(?:\\[\\])+|)|, |\\.)+)\\>\\)(\\w+)\\.(\\w+)");
	/**
	 * <p>
	 * <img src="https://www.debuggex.com/i/ojlBdjjZhnfH5FwU.png">
	 * <p>
	 * <a href="https://www.debuggex.com/r/ojlBdjjZhnfH5FwU">View on
	 * Debuggex</a>
	 * <p>
	 * Fixes more generic types by converting things like
	 * <p>
	 * <code>this.field2833 = (Map&lt;String, T&gt;)Maps.newHashMap();</code>
	 * <p>
	 * to
	 * <p>
	 * <code>this.field2833 = Maps.newHashMap();</code>
	 * <p>
	 * <var>T</var> is not a class, but a generic type that Procyon failed to
	 * decompile.
	 */
	private Pattern generics2 =
		Pattern
			.compile("\\((?:\\w+)\\<(?:[A-Z\\?]|[A-Z\\?], \\w+|\\w+, [A-Z\\?]|[A-Z\\?], [A-Z\\?])>\\)((?:\\w|\\.|\\(\\))+)");
	/**
	 * <p>
	 * <img src="https://www.debuggex.com/i/kVZtC3FTNidWR86H.png">
	 * <p>
	 * <a href="https://www.debuggex.com/r/kVZtC3FTNidWR86H">View on
	 * Debuggex</a>
	 * <p>
	 * Fixes more generic types by converting things like
	 * <p>
	 * <code>new Qt&lt;Object&gt;(6.0f, 1.0, 1.2);</code>
	 * <p>
	 * to
	 * <p>
	 * <code>new Qt(6.0f, 1.0, 1.2);</code>
	 */
	private Pattern generics3 = Pattern
		.compile("(new (?:\\w|\\.)+)\\<Object\\>(\\(.+\\))");
	private ArrayList<RegexListEntry> regexList =
		new ArrayList<RegexListEntry>();
	
	public GuiController(Gui gui)
	{
		m_gui = gui;
		m_deobfuscator = null;
		m_index = null;
		m_currentObfClass = null;
		m_isDirty = false;
		m_referenceStack = Queues.newArrayDeque();
	}
	
	public boolean isDirty()
	{
		return m_isDirty;
	}
	
	public void openJar(final JarFile jar) throws IOException
	{
		m_gui.onStartOpenJar();
		m_deobfuscator = new Deobfuscator(jar);
		m_gui.onFinishOpenJar(m_deobfuscator.getJarName());
		refreshClasses();
	}
	
	public void closeJar()
	{
		m_deobfuscator = null;
		m_gui.onCloseJar();
	}
	
	public void openMappings(File file) throws IOException,
		MappingParseException
	{
		FileReader in = new FileReader(file);
		m_deobfuscator.setMappings(new MappingsReader().read(in));
		in.close();
		m_isDirty = false;
		m_gui.setMappingsFile(file);
		refreshClasses();
		refreshCurrentClass();
	}
	
	public void saveMappings(File file) throws IOException
	{
		FileWriter out = new FileWriter(file);
		new MappingsWriter().write(out, m_deobfuscator.getMappings());
		out.close();
		m_isDirty = false;
	}
	
	public void closeMappings()
	{
		m_deobfuscator.setMappings(null);
		m_gui.setMappingsFile(null);
		refreshClasses();
		refreshCurrentClass();
	}
	
	public void openRegexList(File file)
	{
		try
		{
			regexList = new RegexListReader().read(file);
		}catch(FileNotFoundException e)
		{
			e.printStackTrace();
			JOptionPane.showMessageDialog(m_gui.getFrame(),
				e.getLocalizedMessage(), "File not found",
				JOptionPane.ERROR_MESSAGE);
		}catch(IOException e)
		{
			e.printStackTrace();
			JTextArea message = new JTextArea(e.getLocalizedMessage());
			message.setEditable(false);
			message.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JOptionPane.showMessageDialog(m_gui.getFrame(), message,
				"File could not be read", JOptionPane.ERROR_MESSAGE);
		}
	}
	
	public void closeRegexList()
	{
		regexList.clear();
	}
	
	public void exportSource(final File dirOut)
	{
		ProgressDialog.runInThread(m_gui.getFrame(), new ProgressRunnable()
		{
			@Override
			public void run(ProgressListener progress) throws Exception
			{
				m_deobfuscator.writeSources(dirOut, progress);
			}
		});
	}
	
	public void wurstExportSource(final File dirOut)
	{
		m_currentObfClass = null;
		ProgressDialog.runInThread(m_gui.getFrame(), new ProgressRunnable()
		{
			@Override
			public void run(ProgressListener progress) throws Exception
			{
				progress.init(m_deobfuscator.getJarIndex().getObfClassEntries()
					.size(), "Preparing classes...");
				int i = 0;
				for(ClassEntry entry : m_deobfuscator.getJarIndex()
					.getObfClassEntries())
				{
					if(!entry.isInnerClass())
					{
						progress.onProgress(i++, entry.getName());
						continue;
					}
					// That string will likely never occur anywhere
					// TODO: Allow the user to specify a custom string
					String name = "WurstWurstWurstAllesWirdAusWurstGemacht";
					try
					{
						name +=
							m_deobfuscator
								.getMappings()
								.getClassByObf(entry.getOuterClassName())
								.getDeobfInnerClassName(
									entry.getInnermostClassName());
					}catch(Exception e1)
					{
						name += entry.getInnermostClassName();
					}
					try
					{
						EntryReference<Entry, Entry> obfReference =
							m_deobfuscator
								.obfuscateReference(new EntryReference<Entry, Entry>(
									entry, entry.getName()));
						m_deobfuscator.rename(obfReference.getNameableEntry(),
							name);
					}catch(IllegalNameException e)
					{
						e.printStackTrace();
					}
					progress.onProgress(i++, entry.getName());
				}
				// get the classes to decompile
				Set<ClassEntry> classEntries = Sets.newHashSet();
				for(ClassEntry obfClassEntry : m_deobfuscator.getJarIndex()
					.getObfClassEntries())
				{
					// skip inner classes
					if(obfClassEntry.isInnerClass())
						continue;
					
					classEntries.add(obfClassEntry);
				}
				
				if(progress != null)
					progress.init(classEntries.size(), "Decompiling classes...");
				
				// DEOBFUSCATE ALL THE THINGS!! @_@
				i = 0;
				for(ClassEntry obfClassEntry : classEntries)
				{
					ClassEntry deobfClassEntry =
						m_deobfuscator.deobfuscateEntry(new ClassEntry(
							obfClassEntry));
					if(progress != null)
						progress.onProgress(i++, deobfClassEntry.toString());
					
					try
					{
						// get the source
						String source =
							m_deobfuscator.getSource(m_deobfuscator
								.getSourceTree(obfClassEntry.getName()));
						
						// fix inner class references
						source =
							source
								.replace(
									"$WurstWurstWurstAllesWirdAusWurstGemacht",
									".");
						source =
							source.replace(
								"WurstWurstWurstAllesWirdAusWurstGemacht", "");
						
						// fix generic types
						source =
							generics.matcher(source).replaceAll(
								"$2\\.\\<$1\\>$3");
						source = generics2.matcher(source).replaceAll("$1");
						source = generics3.matcher(source).replaceAll("$1$2");
						
						// apply custom regexes
						try
						{
							for(RegexListEntry entry : regexList)
								if(entry.isTarget(obfClassEntry.getName()))
									source = entry.replaceAll(source);
						}catch(IllegalArgumentException e)
						{
							e.printStackTrace();
							JOptionPane.showMessageDialog(m_gui.getFrame(),
								"Regex list contains invalid replacement(s).\n"
									+ "Export aborted.", "Invalid regex list",
								JOptionPane.ERROR_MESSAGE);
							return;
						}
						
						// write the file
						File file =
							new File(dirOut, deobfClassEntry.getName().replace(
								'.', '/')
								+ ".java");
						file.getParentFile().mkdirs();
						try(FileWriter out = new FileWriter(file))
						{
							out.write(source);
						}
					}catch(Throwable t)
					{
						throw new Error("Unable to deobfuscate class "
							+ deobfClassEntry.toString() + " ("
							+ obfClassEntry.toString() + ")", t);
					}
				}
				// FIXME: Changing the names back
				// progress.init(m_deobfuscator.getJarIndex().getObfClassEntries()
				// .size(), "Renaming inner classes back...");
				// i = 0;
				// for(ClassEntry entry : m_deobfuscator.getJarIndex()
				// .getObfClassEntries())
				// {
				// if(!entry.isInnerClass())
				// {
				// progress.onProgress(i++, entry.getName());
				// continue;
				// }
				// String name = entry.getInnermostClassName();
				// if(name
				// .startsWith("WurstWurstWurstAllesWirdAusWurstGemacht"))
				// name =
				// name.substring("WurstWurstWurstAllesWirdAusWurstGemacht"
				// .length());
				// try
				// {
				// EntryReference<Entry, Entry> obfReference =
				// m_deobfuscator
				// .obfuscateReference(new EntryReference<Entry, Entry>(
				// entry, entry.getName()));
				// m_deobfuscator.rename(obfReference.getNameableEntry(),
				// name);
				// }catch(IllegalNameException e)
				// {
				// e.printStackTrace();
				// }
				// progress.onProgress(i++, name);
				// }
			}
		});
	}
	
	public void exportJar(final File fileOut)
	{
		ProgressDialog.runInThread(m_gui.getFrame(), new ProgressRunnable()
		{
			@Override
			public void run(ProgressListener progress)
			{
				m_deobfuscator.writeJar(fileOut, progress);
			}
		});
	}
	
	public Token getToken(int pos)
	{
		if(m_index == null)
			return null;
		return m_index.getReferenceToken(pos);
	}
	
	public EntryReference<Entry, Entry> getDeobfReference(Token token)
	{
		if(m_index == null)
			return null;
		return m_index.getDeobfReference(token);
	}
	
	public ReadableToken getReadableToken(Token token)
	{
		if(m_index == null)
			return null;
		return new ReadableToken(m_index.getLineNumber(token.start),
			m_index.getColumnNumber(token.start),
			m_index.getColumnNumber(token.end));
	}
	
	public boolean entryHasDeobfuscatedName(Entry deobfEntry)
	{
		return m_deobfuscator.hasDeobfuscatedName(m_deobfuscator
			.obfuscateEntry(deobfEntry));
	}
	
	public boolean entryIsInJar(Entry deobfEntry)
	{
		return m_deobfuscator.isObfuscatedIdentifier(m_deobfuscator
			.obfuscateEntry(deobfEntry));
	}
	
	public boolean referenceIsRenameable(
		EntryReference<Entry, Entry> deobfReference)
	{
		return m_deobfuscator.isRenameable(m_deobfuscator
			.obfuscateReference(deobfReference));
	}
	
	public ClassInheritanceTreeNode getClassInheritance(
		ClassEntry deobfClassEntry)
	{
		ClassEntry obfClassEntry =
			m_deobfuscator.obfuscateEntry(deobfClassEntry);
		ClassInheritanceTreeNode rootNode =
			m_deobfuscator
				.getJarIndex()
				.getClassInheritance(
					m_deobfuscator
						.getTranslator(TranslationDirection.Deobfuscating),
					obfClassEntry);
		return ClassInheritanceTreeNode.findNode(rootNode, obfClassEntry);
	}
	
	public ClassImplementationsTreeNode getClassImplementations(
		ClassEntry deobfClassEntry)
	{
		ClassEntry obfClassEntry =
			m_deobfuscator.obfuscateEntry(deobfClassEntry);
		return m_deobfuscator.getJarIndex().getClassImplementations(
			m_deobfuscator.getTranslator(TranslationDirection.Deobfuscating),
			obfClassEntry);
	}
	
	public MethodInheritanceTreeNode getMethodInheritance(
		MethodEntry deobfMethodEntry)
	{
		MethodEntry obfMethodEntry =
			m_deobfuscator.obfuscateEntry(deobfMethodEntry);
		MethodInheritanceTreeNode rootNode =
			m_deobfuscator
				.getJarIndex()
				.getMethodInheritance(
					m_deobfuscator
						.getTranslator(TranslationDirection.Deobfuscating),
					obfMethodEntry);
		return MethodInheritanceTreeNode.findNode(rootNode, obfMethodEntry);
	}
	
	public MethodImplementationsTreeNode getMethodImplementations(
		MethodEntry deobfMethodEntry)
	{
		MethodEntry obfMethodEntry =
			m_deobfuscator.obfuscateEntry(deobfMethodEntry);
		List<MethodImplementationsTreeNode> rootNodes =
			m_deobfuscator
				.getJarIndex()
				.getMethodImplementations(
					m_deobfuscator
						.getTranslator(TranslationDirection.Deobfuscating),
					obfMethodEntry);
		if(rootNodes.isEmpty())
			return null;
		if(rootNodes.size() > 1)
			System.err.println("WARNING: Method " + deobfMethodEntry
				+ " implements multiple interfaces. Only showing first one.");
		return MethodImplementationsTreeNode.findNode(rootNodes.get(0),
			obfMethodEntry);
	}
	
	public FieldReferenceTreeNode getFieldReferences(FieldEntry deobfFieldEntry)
	{
		FieldEntry obfFieldEntry =
			m_deobfuscator.obfuscateEntry(deobfFieldEntry);
		FieldReferenceTreeNode rootNode =
			new FieldReferenceTreeNode(
				m_deobfuscator
					.getTranslator(TranslationDirection.Deobfuscating),
				obfFieldEntry);
		rootNode.load(m_deobfuscator.getJarIndex(), true);
		return rootNode;
	}
	
	public BehaviorReferenceTreeNode getMethodReferences(
		BehaviorEntry deobfBehaviorEntry)
	{
		BehaviorEntry obfBehaviorEntry =
			m_deobfuscator.obfuscateEntry(deobfBehaviorEntry);
		BehaviorReferenceTreeNode rootNode =
			new BehaviorReferenceTreeNode(
				m_deobfuscator
					.getTranslator(TranslationDirection.Deobfuscating),
				obfBehaviorEntry);
		rootNode.load(m_deobfuscator.getJarIndex(), true);
		return rootNode;
	}
	
	public void rename(EntryReference<Entry, Entry> deobfReference,
		String newName)
	{
		EntryReference<Entry, Entry> obfReference =
			m_deobfuscator.obfuscateReference(deobfReference);
		m_deobfuscator.rename(obfReference.getNameableEntry(), newName);
		m_isDirty = true;
		refreshClasses();
		refreshCurrentClass(obfReference);
	}
	
	public void fixNames()
	{
		if(m_deobfuscator == null)
		{
			JOptionPane.showMessageDialog(m_gui.getFrame(),
				"Cannot fix names because no classes are present.",
				"No classes", JOptionPane.ERROR_MESSAGE);
			return;
		}
		m_currentObfClass = null;
		ProgressDialog.runInThread(m_gui.getFrame(), new ProgressRunnable()
		{
			@Override
			public void run(ProgressListener progress) throws Exception
			{
				progress.init(m_deobfuscator.getJarIndex().getObfFieldEntries()
					.size(), "Fixing field names...");
				counter.set(0);
				int i = 0;
				for(FieldEntry entry : m_deobfuscator.getJarIndex()
					.getObfFieldEntries())
				{
					String name = entry.getName();
					name = "field" + counter.incrementAndGet();
					if(!name.equals(entry.getName()))
						try
						{
							rename(new EntryReference<Entry, Entry>(entry,
								entry.getName()), name);
						}catch(IllegalNameException e)
						{}
					progress.onProgress(i++, name);
				}
				progress.init(m_deobfuscator.getJarIndex().getObfClassEntries()
					.size(), "Fixing class names...");
				counter.set(0);
				i = 0;
				for(ClassEntry entry : m_deobfuscator.getJarIndex()
					.getObfClassEntries())
				{
					String name = entry.getName();
					if(entry.isInnerClass())
						name = "Class" + counter.incrementAndGet();
					else
						name =
							name.substring(0, name.lastIndexOf("/") + 1)
								+ Character.toUpperCase(name.charAt(name
									.lastIndexOf("/") + 1))
								+ (entry.getSimpleName().length() == 1
									? counter.incrementAndGet() : name
										.substring(name.lastIndexOf("/") + 2));
					if(!name.equals(entry.getName()))
						try
						{
							rename(new EntryReference<Entry, Entry>(entry,
								entry.getName()), name);
						}catch(IllegalNameException e)
						{}
					progress.onProgress(i++, name);
				}
			}
		});
	}
	
	public void removeMapping(EntryReference<Entry, Entry> deobfReference)
	{
		EntryReference<Entry, Entry> obfReference =
			m_deobfuscator.obfuscateReference(deobfReference);
		m_deobfuscator.removeMapping(obfReference.getNameableEntry());
		m_isDirty = true;
		refreshClasses();
		refreshCurrentClass(obfReference);
	}
	
	public void markAsDeobfuscated(EntryReference<Entry, Entry> deobfReference)
	{
		EntryReference<Entry, Entry> obfReference =
			m_deobfuscator.obfuscateReference(deobfReference);
		m_deobfuscator.markAsDeobfuscated(obfReference.getNameableEntry());
		m_isDirty = true;
		refreshClasses();
		refreshCurrentClass(obfReference);
	}
	
	public void openDeclaration(Entry deobfEntry)
	{
		if(deobfEntry == null)
			throw new IllegalArgumentException("Entry cannot be null!");
		openReference(new EntryReference<Entry, Entry>(deobfEntry,
			deobfEntry.getName()));
	}
	
	public void openReference(EntryReference<Entry, Entry> deobfReference)
	{
		if(deobfReference == null)
			throw new IllegalArgumentException("Reference cannot be null!");
		
		// get the reference target class
		EntryReference<Entry, Entry> obfReference =
			m_deobfuscator.obfuscateReference(deobfReference);
		ClassEntry obfClassEntry =
			obfReference.getLocationClassEntry().getOutermostClassEntry();
		if(!m_deobfuscator.isObfuscatedIdentifier(obfClassEntry))
			throw new IllegalArgumentException("Obfuscated class "
				+ obfClassEntry + " was not found in the jar!");
		if(m_currentObfClass == null
			|| !m_currentObfClass.equals(obfClassEntry))
		{
			// deobfuscate the class, then navigate to the reference
			m_currentObfClass = obfClassEntry;
			deobfuscate(m_currentObfClass, obfReference);
		}else
			showReference(obfReference);
	}
	
	private void showReference(EntryReference<Entry, Entry> obfReference)
	{
		EntryReference<Entry, Entry> deobfReference =
			m_deobfuscator.deobfuscateReference(obfReference);
		Collection<Token> tokens = m_index.getReferenceTokens(deobfReference);
		if(tokens.isEmpty())
			// DEBUG
			System.err.println(String.format(
				"WARNING: no tokens found for %s in %s", deobfReference,
				m_currentObfClass));
		else
			m_gui.showTokens(tokens);
	}
	
	public void savePreviousReference(
		EntryReference<Entry, Entry> deobfReference)
	{
		m_referenceStack
			.push(m_deobfuscator.obfuscateReference(deobfReference));
	}
	
	public void openPreviousReference()
	{
		if(hasPreviousLocation())
			openReference(m_deobfuscator.deobfuscateReference(m_referenceStack
				.pop()));
	}
	
	public boolean hasPreviousLocation()
	{
		return !m_referenceStack.isEmpty();
	}
	
	private void refreshClasses()
	{
		List<ClassEntry> obfClasses = Lists.newArrayList();
		List<ClassEntry> deobfClasses = Lists.newArrayList();
		m_deobfuscator.getSeparatedClasses(obfClasses, deobfClasses);
		m_gui.setObfClasses(obfClasses);
		m_gui.setDeobfClasses(deobfClasses);
	}
	
	private void refreshCurrentClass()
	{
		refreshCurrentClass(null);
	}
	
	private void refreshCurrentClass(EntryReference<Entry, Entry> obfReference)
	{
		if(m_currentObfClass != null)
			deobfuscate(m_currentObfClass, obfReference);
	}
	
	private void deobfuscate(final ClassEntry classEntry,
		final EntryReference<Entry, Entry> obfReference)
	{
		
		m_gui.setSource("(deobfuscating...)");
		
		// run the deobfuscator in a separate thread so we don't block the GUI
		// event queue
		new Thread()
		{
			@Override
			public void run()
			{
				// decompile,deobfuscate the bytecode
				CompilationUnit sourceTree =
					m_deobfuscator.getSourceTree(classEntry.getClassName());
				if(sourceTree == null)
				{
					// decompilation of this class is not supported
					m_gui.setSource("Unable to find class: " + classEntry);
					return;
				}
				String source = m_deobfuscator.getSource(sourceTree);
				m_index = m_deobfuscator.getSourceIndex(sourceTree, source);
				m_gui.setSource(m_index.getSource());
				if(obfReference != null)
					showReference(obfReference);
				
				// set the highlighted tokens
				List<Token> obfuscatedTokens = Lists.newArrayList();
				List<Token> deobfuscatedTokens = Lists.newArrayList();
				List<Token> otherTokens = Lists.newArrayList();
				for(Token token : m_index.referenceTokens())
				{
					EntryReference<Entry, Entry> reference =
						m_index.getDeobfReference(token);
					if(referenceIsRenameable(reference))
					{
						if(entryHasDeobfuscatedName(reference
							.getNameableEntry()))
							deobfuscatedTokens.add(token);
						else
							obfuscatedTokens.add(token);
					}else
						otherTokens.add(token);
				}
				m_gui.setHighlightedTokens(obfuscatedTokens,
					deobfuscatedTokens, otherTokens);
			}
		}.start();
	}
}
