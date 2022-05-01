/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013-2019 Minecraft Forge
 * Copyright (C) 2020-2022 anatawa12 and other contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.minecraftforge.gradle;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.apache.logging.log4j.Level;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

public class GradleForgeHacks
{

    /* ----------- COREMOD AND AT HACK --------- */
    private static final String           NO_CORE_SEARCH = "--noCoreSearch";

    // coremod hack
    private static final String           COREMOD_VAR    = "fml.coreMods.load";
    private static final String           COREMOD_MF     = "FMLCorePlugin";
    // AT hack
    private static final String           MOD_ATD_CLASS  = "net.minecraftforge.fml.common.asm.transformers.ModAccessTransformer";
    private static final String           MOD_AT_METHOD  = "addJar";

    public static final Map<String, File> coreMap        = Maps.newHashMap();

    public static void searchCoremods(GradleStartCommon common)
    {
        // check for argument
        if (common.extras.contains(NO_CORE_SEARCH))
        {
            // no core searching
            GradleStartCommon.LOGGER.info("GradleStart coremod searching disabled!");

            // remove it so it cant potentially screw up the bonced start class
            common.extras.remove(NO_CORE_SEARCH);

            return;
        }

        // initialize AT hack Method
        AtRegistrar atRegistrar = new AtRegistrar();

        URLClassLoader urlClassLoader = (URLClassLoader) GradleStartCommon.class.getClassLoader();
        for (URL url : urlClassLoader.getURLs())
        {
            try
            {
                searchCoremodAtUrl(url, atRegistrar);
            }
            catch (IOException | InvocationTargetException | IllegalAccessException | URISyntaxException e)
            {
                GradleStartCommon.LOGGER.warn("GradleForgeHacks failed to search for coremod at url {}", url, e);
            }
        }

        // set property.
        Set<String> coremodsSet = Sets.newHashSet();
        if (!Strings.isNullOrEmpty(System.getProperty(COREMOD_VAR)))
            coremodsSet.addAll(Splitter.on(',').splitToList(System.getProperty(COREMOD_VAR)));
        coremodsSet.addAll(coreMap.keySet());
        System.setProperty(COREMOD_VAR, Joiner.on(',').join(coremodsSet));

        // ok.. tweaker hack now.
        if (!Strings.isNullOrEmpty(common.getTweakClass()))
        {
            common.extras.add("--tweakClass");
            common.extras.add("net.minecraftforge.gradle.tweakers.CoremodTweaker");
        }
    }

    private static void searchCoremodAtUrl(URL url, AtRegistrar atRegistrar) throws IOException, InvocationTargetException, IllegalAccessException, URISyntaxException
    {
        if (!url.getProtocol().startsWith("file")) // because file urls start with file://
            return; //         this isn't a file

        File coreMod = new File(url.toURI().getPath());
        Manifest manifest = null;

        if (!coreMod.exists())
            return;

        if (coreMod.isDirectory())
        {
            File manifestMF = new File(coreMod, "META-INF/MANIFEST.MF");
            if (manifestMF.exists())
            {
                FileInputStream stream = new FileInputStream(manifestMF);
                manifest = new Manifest(stream);
                stream.close();
            }
        }
        else if (coreMod.getName().endsWith("jar")) // its a jar
        {
            try (JarFile jar = new JarFile(coreMod))
            {
                manifest = jar.getManifest();
                if (manifest != null)
                {
                    atRegistrar.addJar(jar, manifest);
                }
            }
        }

        // we got the manifest? use it.
        if (manifest != null)
        {
            String clazz = manifest.getMainAttributes().getValue(COREMOD_MF);
            if (!Strings.isNullOrEmpty(clazz))
            {
                GradleStartCommon.LOGGER.info("Found and added coremod: " + clazz);
                coreMap.put(clazz, coreMod);
            }
        }
    }

    /**
     * Hack to register jar ATs with Minecraft Forge
     */
    private static final class AtRegistrar
    {
        private static final Attributes.Name FMLAT = new Attributes.Name("FMLAT");

        @Nullable
        private Method newMethod = null;
        @Nullable
        private Method oldMethod = null;

        private AtRegistrar()
        {
            try
            {
                Class<?> modAtdClass = Class.forName(MOD_ATD_CLASS);
                try
                {
                    newMethod = modAtdClass.getDeclaredMethod(MOD_AT_METHOD, JarFile.class, String.class);
                }
                catch (NoSuchMethodException | SecurityException ignored)
                {
                    try
                    {
                        oldMethod = modAtdClass.getDeclaredMethod(MOD_AT_METHOD, JarFile.class);
                    }
                    catch (NoSuchMethodException | SecurityException ignored2)
                    {
                        GradleStartCommon.LOGGER.error("Failed to find method {}.{}", MOD_ATD_CLASS, MOD_AT_METHOD);
                    }
                }
            }
            catch (ClassNotFoundException e)
            {
                GradleStartCommon.LOGGER.error("Failed to find class {}", MOD_ATD_CLASS);
            }
        }

        public void addJar(JarFile jarFile, Manifest manifest) throws InvocationTargetException, IllegalAccessException
        {
            if (newMethod != null)
            {
                String ats = manifest.getMainAttributes().getValue(FMLAT);
                if (ats != null && !ats.isEmpty())
                {
                    newMethod.invoke(null, jarFile, ats);
                }
            }
            else if (oldMethod != null)
            {
                oldMethod.invoke(null, jarFile);
            }
        }
    }

    /* ----------- CUSTOM TWEAKER FOR COREMOD HACK --------- */

    // here and not in the tweaker package because classloader hell
    public static final class AccessTransformerTransformer implements IClassTransformer
    {
        public AccessTransformerTransformer()
        {
            doStuff((LaunchClassLoader) getClass().getClassLoader());
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private void doStuff(LaunchClassLoader classloader)
        {
            // the class and instance of ModAccessTransformer
            Class<? extends IClassTransformer> clazz = null;
            IClassTransformer instance = null;

            // find the instance I want. AND grab the type too, since thats better than Class.forName()
            for (IClassTransformer transformer : classloader.getTransformers())
            {
                if (transformer.getClass().getCanonicalName().endsWith(MOD_ATD_CLASS))
                {
                    clazz = transformer.getClass();
                    instance = transformer;
                }
            }

            // impossible! but i will ignore it.
            if (clazz == null || instance == null)
            {
                GradleStartCommon.LOGGER.log(Level.ERROR, "ModAccessTransformer was somehow not found.");
                return;
            }

            // grab the list of Modifiers I wanna mess with
            Collection<Object> modifiers = null;
            try
            {
                // super class of ModAccessTransformer is AccessTransformer
                Field f = clazz.getSuperclass().getDeclaredFields()[1]; // its the modifiers map. Only non-static field there.
                f.setAccessible(true);

                modifiers = ((com.google.common.collect.Multimap) f.get(instance)).values();
            }
            catch (Throwable t)
            {
                GradleStartCommon.LOGGER.log(Level.ERROR, "AccessTransformer.modifiers field was somehow not found...");
                return;
            }

            if (modifiers.isEmpty())
            {
                return; // hell no am I gonna do stuff if its empty..
            }

            // grab the field I wanna hack
            Field nameField = null;
            try
            {
                // get 1 from the collection
                Object mod = null;
                for (Object val : modifiers)
                {
                    mod = val;
                    break;
                } // i wish this was cleaner

                nameField = mod.getClass().getFields()[0]; // first field. "name"
                nameField.setAccessible(true); // its alreadypublic, but just in case
            }
            catch (Throwable t)
            {
                GradleStartCommon.LOGGER.log(Level.ERROR, "AccessTransformer.Modifier.name field was somehow not found...");
                return;
            }

            // read the field and method CSV files.
            Map<String, String> nameMap = Maps.newHashMap();
            try
            {
                readCsv(new File(GradleStartCommon.CSV_DIR, "fields.csv"), nameMap);
                readCsv(new File(GradleStartCommon.CSV_DIR, "methods.csv"), nameMap);
            }
            catch (IOException e)
            {
                // If I cant find these.. something is terribly wrong.
                GradleStartCommon.LOGGER.log(Level.ERROR, "Could not load CSV files!");
                e.printStackTrace();
                return;
            }

            GradleStartCommon.LOGGER.log(Level.INFO, "Remapping AccessTransformer rules...");

            // finally hit the modifiers
            for (Object modifier : modifiers) // these are instances of AccessTransformer.Modifier
            {
                String name;
                try
                {
                    name = (String) nameField.get(modifier);
                    String newName = nameMap.get(name);
                    if (newName != null)
                    {
                        nameField.set(modifier, newName);
                    }
                }
                catch (Exception e)
                {
                    // impossible. It would have failed earlier if possible.
                }
            }
        }

        private void readCsv(File file, Map<String, String> map) throws IOException
        {
            GradleStartCommon.LOGGER.log(Level.DEBUG, "Reading CSV file: {}", file);
            Splitter split = Splitter.on(',').trimResults().limit(3);
            for (String line : Files.readLines(file, Charsets.UTF_8))
            {
                if (line.startsWith("searge")) // header line
                    continue;

                List<String> splits = split.splitToList(line);
                map.put(splits.get(0), splits.get(1));
            }
        }

        @Override
        public byte[] transform(String name, String transformedName, byte[] basicClass)
        {
            return basicClass; // nothing here
        }
    }
}
